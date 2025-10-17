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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Eelink命令请求接口
 */
public interface EelinkCommandRequest {
    
    /**
     * 获取帧代号
     */
    byte getFrameCode();
    
    /**
     * 编码为Eelink协议帧
     */
    ByteBuf encodeToFrame(ByteBufAllocator allocator, byte[] deviceAddress);
    
    /**
     * 获取RPC请求ID（用于响应匹配）
     */
    int getRequestId();
}

