/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.ops;

import io.netty.buffer.DrillBuf;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.hydromatic.optiq.SchemaPlus;
import net.hydromatic.optiq.jdbc.SimpleOptiqSchema;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.exec.exception.ClassTransformationException;
import org.apache.drill.exec.expr.ClassGenerator;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.expr.fn.FunctionImplementationRegistry;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.apache.drill.exec.proto.BitControl.PlanFragment;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.proto.ExecProtos.FragmentHandle;
import org.apache.drill.exec.proto.GeneralRPCProtos.Ack;
import org.apache.drill.exec.rpc.RpcException;
import org.apache.drill.exec.rpc.RpcOutcomeListener;
import org.apache.drill.exec.rpc.control.ControlTunnel;
import org.apache.drill.exec.rpc.user.UserServer.UserClientConnection;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.server.options.FragmentOptionManager;
import org.apache.drill.exec.server.options.OptionList;
import org.apache.drill.exec.server.options.OptionManager;
import org.apache.drill.exec.store.PartitionExplorer;
import org.apache.drill.exec.work.batch.IncomingBuffers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 * Contextual objects required for execution of a particular fragment.
 */
public class FragmentContext implements AutoCloseable, UdfUtilities {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FragmentContext.class);

  private final Map<DrillbitEndpoint, AccountingDataTunnel> tunnels = Maps.newHashMap();
  private final DrillbitContext context;
  private final UserClientConnection connection;
  private final FragmentStats stats;
  private final FunctionImplementationRegistry funcRegistry;
  private final BufferAllocator allocator;
  private final PlanFragment fragment;
  private final QueryDateTimeInfo queryDateTimeInfo;
  private IncomingBuffers buffers;
  private final OptionManager fragmentOptions;
  private final BufferManager bufferManager;
  private ExecutorState executorState;

  private final SendingAccountor sendingAccountor = new SendingAccountor();
  private final Consumer<RpcException> exceptionConsumer = new Consumer<RpcException>() {
    @Override
    public void accept(final RpcException e) {
      fail(e);
    }
  };

  private final RpcOutcomeListener<Ack> statusHandler = new StatusHandler(exceptionConsumer, sendingAccountor);
  private final AccountingUserConnection accountingUserConnection;

  public FragmentContext(final DrillbitContext dbContext, final PlanFragment fragment,
      final UserClientConnection connection, final FunctionImplementationRegistry funcRegistry)
    throws ExecutionSetupException {
    this.context = dbContext;
    this.connection = connection;
    this.accountingUserConnection = new AccountingUserConnection(connection, sendingAccountor, statusHandler);
    this.fragment = fragment;
    this.funcRegistry = funcRegistry;
    queryDateTimeInfo = new QueryDateTimeInfo(fragment.getQueryStartTime(), fragment.getTimeZone());

    logger.debug("Getting initial memory allocation of {}", fragment.getMemInitial());
    logger.debug("Fragment max allocation: {}", fragment.getMemMax());

    try {
      final OptionList list;
      if (!fragment.hasOptionsJson() || fragment.getOptionsJson().isEmpty()) {
        list = new OptionList();
      } else {
        list = dbContext.getConfig().getMapper().readValue(fragment.getOptionsJson(), OptionList.class);
      }
      fragmentOptions = new FragmentOptionManager(context.getOptionManager(), list);
    } catch (final Exception e) {
      throw new ExecutionSetupException("Failure while reading plan options.", e);
    }

    // Add the fragment context to the root allocator.
    // The QueryManager will call the root allocator to recalculate all the memory limits for all the fragments
    try {
      allocator = context.getAllocator().getChildAllocator(
          this, fragment.getMemInitial(), fragment.getMemMax(), true);
      Preconditions.checkNotNull(allocator, "Unable to acuqire allocator");
    } catch(final Throwable e) {
      throw new ExecutionSetupException("Failure while getting memory allocator for fragment.", e);
    }

    stats = new FragmentStats(allocator, dbContext.getMetrics(), fragment.getAssignment());
    bufferManager = new BufferManager(this.allocator, this);
  }

  public OptionManager getOptions() {
    return fragmentOptions;
  }

  public void setBuffers(final IncomingBuffers buffers) {
    Preconditions.checkArgument(this.buffers == null, "Can only set buffers once.");
    this.buffers = buffers;
  }

  public void setExecutorState(final ExecutorState executorState) {
    Preconditions.checkArgument(this.executorState == null, "ExecutorState can only be set once.");
    this.executorState = executorState;
  }

  public void fail(final Throwable cause) {
    executorState.fail(cause);
  }

  /**
   * Tells individual operations whether they should continue. In some cases, an external event (typically cancellation)
   * will mean that the fragment should prematurely exit execution. Long running operations should check this every so
   * often so that Drill is responsive to cancellation operations.
   *
   * @return false if the action should terminate immediately, true if everything is okay.
   */
  public boolean shouldContinue() {
    return executorState.shouldContinue();
  }

  public DrillbitContext getDrillbitContext() {
    return context;
  }

  public SchemaPlus getRootSchema() {
    if (connection == null) {
      fail(new UnsupportedOperationException("Schema tree can only be created in root fragment. " +
          "This is a non-root fragment."));
      return null;
    }

    final SchemaPlus root = SimpleOptiqSchema.createRootSchema(false);
    context.getStorage().getSchemaFactory().registerSchemas(connection.getSession(), root);
    return root;
  }

  /**
   * Get this node's identity.
   * @return A DrillbitEndpoint object.
   */
  public DrillbitEndpoint getIdentity() {
    return context.getEndpoint();
  }

  public FragmentStats getStats() {
    return stats;
  }

  @Override
  public QueryDateTimeInfo getQueryDateTimeInfo(){
    return this.queryDateTimeInfo;
  }

  public DrillbitEndpoint getForemanEndpoint() {
    return fragment.getForeman();
  }

  /**
   * The FragmentHandle for this Fragment
   * @return FragmentHandle
   */
  public FragmentHandle getHandle() {
    return fragment.getHandle();
  }

  private String getFragIdString() {
    final FragmentHandle handle = getHandle();
    final String frag = handle != null ? handle.getMajorFragmentId() + ":" + handle.getMinorFragmentId() : "0:0";
    return frag;
  }



  /**
   * Get this fragment's allocator.
   * @return the allocator
   */
  @Deprecated
  public BufferAllocator getAllocator() {
    if (allocator == null) {
      logger.debug("Fragment: " + getFragIdString() + " Allocator is NULL");
    }
    return allocator;
  }

  public BufferAllocator getNewChildAllocator(final long initialReservation,
                                              final long maximumReservation,
                                              final boolean applyFragmentLimit) throws OutOfMemoryException {
    return allocator.getChildAllocator(this, initialReservation, maximumReservation, applyFragmentLimit);
  }

  public <T> T getImplementationClass(final ClassGenerator<T> cg)
      throws ClassTransformationException, IOException {
    return getImplementationClass(cg.getCodeGenerator());
  }

  public <T> T getImplementationClass(final CodeGenerator<T> cg)
      throws ClassTransformationException, IOException {
    return context.getCompiler().getImplementationClass(cg);
  }

  public <T> List<T> getImplementationClass(final ClassGenerator<T> cg, final int instanceCount) throws ClassTransformationException, IOException {
    return getImplementationClass(cg.getCodeGenerator(), instanceCount);
  }

  public <T> List<T> getImplementationClass(final CodeGenerator<T> cg, final int instanceCount) throws ClassTransformationException, IOException {
    return context.getCompiler().getImplementationClass(cg, instanceCount);
  }

  public AccountingUserConnection getUserDataTunnel() {
    Preconditions.checkState(connection != null, "Only Root fragment can get UserDataTunnel");
    return accountingUserConnection;
  }

  public ControlTunnel getControlTunnel(final DrillbitEndpoint endpoint) {
    return context.getController().getTunnel(endpoint);
  }

  public AccountingDataTunnel getDataTunnel(final DrillbitEndpoint endpoint) {
    AccountingDataTunnel tunnel = tunnels.get(endpoint);
    if (tunnel == null) {
      tunnel = new AccountingDataTunnel(context.getDataConnectionsPool().getTunnel(endpoint), sendingAccountor, statusHandler);
      tunnels.put(endpoint, tunnel);
    }
    return tunnel;
  }

  public IncomingBuffers getBuffers() {
    return buffers;
  }

  @VisibleForTesting
  @Deprecated
  public Throwable getFailureCause() {
    return executorState.getFailureCause();
  }

  @VisibleForTesting
  @Deprecated
  public boolean isFailed() {
    return executorState.isFailed();
  }

  public FunctionImplementationRegistry getFunctionRegistry() {
    return funcRegistry;
  }

  public DrillConfig getConfig() {
    return context.getConfig();
  }

  public void setFragmentLimit(final long limit) {
    allocator.setFragmentLimit(limit);
  }

  @Override
  public void close() {
    waitForSendComplete();
    suppressingClose(bufferManager);
    suppressingClose(buffers);
    suppressingClose(allocator);
  }

  private void suppressingClose(final AutoCloseable closeable) {
    try {
      if (closeable != null) {
        closeable.close();
      }
    } catch (final Exception e) {
      fail(e);
    }
  }

  public DrillBuf replace(final DrillBuf old, final int newSize) {
    return bufferManager.replace(old, newSize);
  }

  @Override
  public DrillBuf getManagedBuffer() {
    return bufferManager.getManagedBuffer();
  }

  public DrillBuf getManagedBuffer(final int size) {
    return bufferManager.getManagedBuffer(size);
  }

  @Override
  public PartitionExplorer getPartitionExplorer() {
    throw new UnsupportedOperationException(String.format("The partition explorer interface can only be used " +
        "in functions that can be evaluated at planning time. Make sure that the %s configuration " +
        "option is set to true.", PlannerSettings.CONSTANT_FOLDING.getOptionName()));
  }

  /**
   * Wait for ack that all outgoing batches have been sent
   */
  public void waitForSendComplete() {
    sendingAccountor.waitForSendComplete();
  }

  public interface ExecutorState {
    /**
     * Whether execution should continue.
     *
     * @return false if execution should stop.
     */
    public boolean shouldContinue();

    /**
     * Inform the executor if a exception occurs and fragment should be failed.
     *
     * @param t
     *          The exception that occurred.
     */
    public void fail(final Throwable t);

    @VisibleForTesting
    @Deprecated
    public boolean isFailed();

    @VisibleForTesting
    @Deprecated
    public Throwable getFailureCause();

  }

}
