// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.client;

import java.util.List;

import com.google.common.base.MoreObjects;

import org.apache.kudu.annotations.InterfaceAudience;

/**
 * Container class for traces. Most of its properties can be null, when they aren't set via the
 * builder. The timestamp is set automatically.
 */
@InterfaceAudience.Private
class RpcTraceFrame {
  enum Action {
    // Just before putting the RPC on the wire.
    SEND_TO_SERVER {
      void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb) {
        sb.append("sending RPC to server ");
        sb.append(trace.getServer().getUuid());
      }
    },
    // Just after parsing the response from the server.
    RECEIVE_FROM_SERVER {
      void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb) {
        sb.append("received from server ");
        sb.append(trace.getServer().getUuid());
        sb.append(" response ");
        sb.append(trace.getStatus());
      }
    },
    // Just before sleeping and then retrying.
    SLEEP_THEN_RETRY {
      void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb) {
        sb.append("delaying RPC due to ");
        sb.append(trace.getStatus());
      }
    },
    // After having figured out that we don't know where the RPC is going,
    // before querying the master.
    QUERY_MASTER {
      void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb) {
        sb.append("querying master");
      }
    },
    // Once the trace becomes too large, will be the last trace object in the list.
    TRACE_TRUNCATED {
      void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb) {
        sb.append("trace too long, truncated");
      }
    };

    abstract void appendToStringBuilder(RpcTraceFrame trace, StringBuilder sb);
  }

  private final String rpcMethod;
  private final Action action;
  private final ServerInfo serverInfo;
  private final long timestampMs;
  private final Status callStatus;

  private RpcTraceFrame(String rpcMethod, Action action,
                        ServerInfo serverInfo, Status callStatus) {
    this.rpcMethod = rpcMethod;
    this.action = action;
    this.serverInfo = serverInfo;
    this.callStatus = callStatus;
    this.timestampMs = System.currentTimeMillis();
  }

  public String getRpcMethod() {
    return rpcMethod;
  }

  Action getAction() {
    return action;
  }

  ServerInfo getServer() {
    return serverInfo;
  }

  long getTimestampMs() {
    return timestampMs;
  }

  public Status getStatus() {
    return callStatus;
  }

  public static String getHumanReadableStringForTraces(List<RpcTraceFrame> traces) {
    String rootMethod;
    long baseTimestamp;
    if (traces.isEmpty()) {
      return "No traces";
    } else {
      RpcTraceFrame firstTrace = traces.get(0);
      rootMethod = firstTrace.getRpcMethod();
      baseTimestamp = firstTrace.getTimestampMs();
    }

    StringBuilder sb = new StringBuilder("Traces: ");
    for (int i = 0; i < traces.size(); i++) {
      RpcTraceFrame trace = traces.get(i);
      sb.append('[');
      sb.append(trace.getTimestampMs() - baseTimestamp);
      sb.append("ms] ");

      if (!rootMethod.equals(trace.getRpcMethod())) {
        sb.append("Sub rpc: ");
        sb.append(trace.getRpcMethod());
        sb.append(" ");
      }

      trace.getAction().appendToStringBuilder(trace, sb);

      if (i < traces.size() - 1) {
        sb.append(", ");
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("rpcMethod", rpcMethod)
        .add("timestampMs", timestampMs)
        .add("action", action)
        .add("serverInfo", serverInfo)
        .add("callStatus", callStatus)
        .toString();
  }

  /**
   * Builder class for trace frames. The only required parameters are set in the constructor.
   * Timestamp is set automatically.
   */
  static class RpcTraceFrameBuilder {
    private final String rpcMethod;
    private final Action action;
    private ServerInfo serverInfo;
    private Status callStatus;

    RpcTraceFrameBuilder(String rpcMethod, Action action) {
      this.rpcMethod = rpcMethod;
      this.action = action;
    }

    public RpcTraceFrameBuilder serverInfo(ServerInfo serverInfo) {
      this.serverInfo = serverInfo;
      return this;
    }

    public RpcTraceFrameBuilder callStatus(Status callStatus) {
      this.callStatus = callStatus;
      return this;
    }

    public RpcTraceFrame build() {
      return new RpcTraceFrame(rpcMethod, action, serverInfo, callStatus);
    }
  }
}
