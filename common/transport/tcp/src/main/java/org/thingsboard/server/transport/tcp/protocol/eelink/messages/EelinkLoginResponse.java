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
import lombok.Data;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrameEncoder;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig;

/**
 * Eelink设备登陆响应
 * 帧代号：0x41
 * 
 * 成功响应数据段：1字节 0x00（注册成功）
 * 错误响应数据段：2字节 0xCF 0xCF + 1字节错误码
 */
@Data
public class EelinkLoginResponse {
    
    public static final byte FRAME_CODE = 0x41;
    public static final byte FUNCTION_CODE_1 = 0x61;
    public static final byte FUNCTION_CODE_2 = 0x00;
    
    // 响应结果
    public static final byte RESULT_SUCCESS = 0x00;
    
    // 错误标志
    public static final byte ERROR_FLAG_1 = (byte) 0xCF;
    public static final byte ERROR_FLAG_2 = (byte) 0xCF;
    
    // 错误码
    public static final byte ERROR_NOT_CONFIGURED = 0x01;  // 未配置
    public static final byte ERROR_DATA_INVALID = 0x02;    // 数据内容错误
    
    private boolean success;
    private byte errorCode;
    private byte[] deviceAddress;
    
    /**
     * 创建成功响应
     */
    public static EelinkLoginResponse success(byte[] deviceAddress) {
        EelinkLoginResponse response = new EelinkLoginResponse();
        response.success = true;
        response.deviceAddress = deviceAddress;
        return response;
    }
    
    /**
     * 创建错误响应
     */
    public static EelinkLoginResponse error(byte[] deviceAddress, byte errorCode) {
        EelinkLoginResponse response = new EelinkLoginResponse();
        response.success = false;
        response.errorCode = errorCode;
        response.deviceAddress = deviceAddress;
        return response;
    }
    
    /**
     * 编码为完整帧
     */
    public ByteBuf encode(ByteBufAllocator allocator) {
        byte[] data;
        
        if (success) {
            // 成功响应：1字节 0x00
            data = new byte[]{RESULT_SUCCESS};
        } else {
            // 错误响应：0xCF 0xCF + 错误码
            data = new byte[]{ERROR_FLAG_1, ERROR_FLAG_2, errorCode};
        }
        
        return EelinkFrameEncoder.encodeFrame(
                allocator,
                EelinkProtocolConfig.DIRECTION_RESPONSE,  // 应答帧
                FRAME_CODE,
                FUNCTION_CODE_1,
                FUNCTION_CODE_2,
                deviceAddress,
                data
        );
    }
}

