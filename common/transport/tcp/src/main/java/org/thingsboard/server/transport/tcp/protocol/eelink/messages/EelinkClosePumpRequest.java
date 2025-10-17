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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 关泵开阀请求
 * 帧代号：0x01
 * 数据段：1字节（0=泵1泵2, 1=泵1, 2=泵2, 3=阀门1, 4=阀门2, 5=阀门3）
 */
@Data
@Builder
@Slf4j
public class EelinkClosePumpRequest implements EelinkCommandRequest {
    
    public static final byte FRAME_CODE = 0x01;
    public static final byte FUNCTION_CODE_1 = 0x61;
    public static final byte FUNCTION_CODE_2 = 0x01;
    
    /**
     * 设备选择枚举
     */
    public enum DeviceType {
        BOTH_PUMPS(0, "泵1泵2"),
        PUMP1(1, "泵1"),
        PUMP2(2, "泵2"),
        VALVE1(3, "阀门1"),
        VALVE2(4, "阀门2"),
        VALVE3(5, "阀门3");
        
        private final int code;
        private final String name;
        
        DeviceType(int code, String name) {
            this.code = code;
            this.name = name;
        }
        
        public byte getCode() {
            return (byte) code;
        }
        
        public String getDeviceName() {
            return name;
        }
        
        public static DeviceType fromCode(int code) {
            for (DeviceType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid device code: " + code);
        }
    }
    
    private int requestId;       // RPC请求ID
    private DeviceType device;   // 要控制的设备
    
    /**
     * 从RPC参数创建请求
     * RPC示例: {method:"closePump", params:{device:1}} 或 {method:"closePump", params:{device:"PUMP1"}}
     */
    public static EelinkClosePumpRequest fromRpc(int requestId, JsonObject params) {
        DeviceType device = DeviceType.BOTH_PUMPS; // 默认关闭两个泵
        
        if (params != null && params.has("device")) {
            if (params.get("device").isJsonPrimitive()) {
                if (params.get("device").getAsJsonPrimitive().isNumber()) {
                    // 数字形式
                    int deviceCode = params.get("device").getAsInt();
                    device = DeviceType.fromCode(deviceCode);
                } else {
                    // 字符串形式
                    String deviceName = params.get("device").getAsString();
                    device = DeviceType.valueOf(deviceName.toUpperCase());
                }
            }
        }
        
        return EelinkClosePumpRequest.builder()
                .requestId(requestId)
                .device(device)
                .build();
    }
    
    @Override
    public byte getFrameCode() {
        return FRAME_CODE;
    }
    
    @Override
    public int getRequestId() {
        return requestId;
    }
    
    @Override
    public ByteBuf encodeToFrame(ByteBufAllocator allocator, byte[] deviceAddress) {
        // 数据段：只有1字节
        byte[] data = new byte[]{device.getCode()};
        
        log.info("Encoding closePump request: requestId={}, device={}({})", 
                requestId, device.name(), device.getDeviceName());
        
        // 使用EelinkFrameEncoder编码帧
        return org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrameEncoder.encodeFrame(
                allocator,
                org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig.DIRECTION_REQUEST,
                FRAME_CODE,
                FUNCTION_CODE_1,
                FUNCTION_CODE_2,
                deviceAddress,
                data);
    }
}

