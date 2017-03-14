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
// Generated by http://code.google.com/p/protostuff/ ... DO NOT EDIT!
// Generated from protobuf

package org.apache.drill.exec.proto.beans;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import com.dyuproject.protostuff.GraphIOUtil;
import com.dyuproject.protostuff.Input;
import com.dyuproject.protostuff.Message;
import com.dyuproject.protostuff.Output;
import com.dyuproject.protostuff.Schema;

public final class GetServerMetaResp implements Externalizable, Message<GetServerMetaResp>, Schema<GetServerMetaResp>
{

    public static Schema<GetServerMetaResp> getSchema()
    {
        return DEFAULT_INSTANCE;
    }

    public static GetServerMetaResp getDefaultInstance()
    {
        return DEFAULT_INSTANCE;
    }

    static final GetServerMetaResp DEFAULT_INSTANCE = new GetServerMetaResp();

    
    private RequestStatus status;
    private ServerMeta serverMeta;
    private DrillPBError error;

    public GetServerMetaResp()
    {
        
    }

    // getters and setters

    // status

    public RequestStatus getStatus()
    {
        return status == null ? RequestStatus.UNKNOWN_STATUS : status;
    }

    public GetServerMetaResp setStatus(RequestStatus status)
    {
        this.status = status;
        return this;
    }

    // serverMeta

    public ServerMeta getServerMeta()
    {
        return serverMeta;
    }

    public GetServerMetaResp setServerMeta(ServerMeta serverMeta)
    {
        this.serverMeta = serverMeta;
        return this;
    }

    // error

    public DrillPBError getError()
    {
        return error;
    }

    public GetServerMetaResp setError(DrillPBError error)
    {
        this.error = error;
        return this;
    }

    // java serialization

    public void readExternal(ObjectInput in) throws IOException
    {
        GraphIOUtil.mergeDelimitedFrom(in, this, this);
    }

    public void writeExternal(ObjectOutput out) throws IOException
    {
        GraphIOUtil.writeDelimitedTo(out, this, this);
    }

    // message method

    public Schema<GetServerMetaResp> cachedSchema()
    {
        return DEFAULT_INSTANCE;
    }

    // schema methods

    public GetServerMetaResp newMessage()
    {
        return new GetServerMetaResp();
    }

    public Class<GetServerMetaResp> typeClass()
    {
        return GetServerMetaResp.class;
    }

    public String messageName()
    {
        return GetServerMetaResp.class.getSimpleName();
    }

    public String messageFullName()
    {
        return GetServerMetaResp.class.getName();
    }

    public boolean isInitialized(GetServerMetaResp message)
    {
        return true;
    }

    public void mergeFrom(Input input, GetServerMetaResp message) throws IOException
    {
        for(int number = input.readFieldNumber(this);; number = input.readFieldNumber(this))
        {
            switch(number)
            {
                case 0:
                    return;
                case 1:
                    message.status = RequestStatus.valueOf(input.readEnum());
                    break;
                case 2:
                    message.serverMeta = input.mergeObject(message.serverMeta, ServerMeta.getSchema());
                    break;

                case 3:
                    message.error = input.mergeObject(message.error, DrillPBError.getSchema());
                    break;

                default:
                    input.handleUnknownField(number, this);
            }   
        }
    }


    public void writeTo(Output output, GetServerMetaResp message) throws IOException
    {
        if(message.status != null)
             output.writeEnum(1, message.status.number, false);

        if(message.serverMeta != null)
             output.writeObject(2, message.serverMeta, ServerMeta.getSchema(), false);


        if(message.error != null)
             output.writeObject(3, message.error, DrillPBError.getSchema(), false);

    }

    public String getFieldName(int number)
    {
        switch(number)
        {
            case 1: return "status";
            case 2: return "serverMeta";
            case 3: return "error";
            default: return null;
        }
    }

    public int getFieldNumber(String name)
    {
        final Integer number = __fieldMap.get(name);
        return number == null ? 0 : number.intValue();
    }

    private static final java.util.HashMap<String,Integer> __fieldMap = new java.util.HashMap<String,Integer>();
    static
    {
        __fieldMap.put("status", 1);
        __fieldMap.put("serverMeta", 2);
        __fieldMap.put("error", 3);
    }
    
}
