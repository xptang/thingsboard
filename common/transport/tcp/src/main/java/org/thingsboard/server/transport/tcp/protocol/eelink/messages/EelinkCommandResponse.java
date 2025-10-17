/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.tcp.protocol.eelink.messages;

import com.google.gson.JsonObject;

/**
 * Eelink命令响应接口
 */
public interface EelinkCommandResponse {
    
    /**
     * 获取帧代号
     */
    byte getFrameCode();
    
    /**
     * 获取RPC请求ID
     */
    int getRequestId();
    
    /**
     * 设置RPC请求ID
     */
    void setRequestId(int requestId);
    
    /**
     * 是否成功
     */
    boolean isSuccess();
    
    /**
     * 转换为ThingsBoard RPC响应JSON
     */
    JsonObject toRpcResponse();
}

