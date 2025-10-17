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
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 关泵开阀响应
 * 帧代号：0x01
 * 
 * 成功响应数据段：1字节（设备编号）
 * 错误响应数据段：3字节（0xCF 0xCF + 错误码）
 */
@Data
@Slf4j
public class EelinkClosePumpResponse implements EelinkCommandResponse {
    
    public static final byte FRAME_CODE = 0x01;
    
    private int requestId;
    private boolean success;
    private EelinkClosePumpRequest.DeviceType device;
    private byte errorCode;
    
    /**
     * 解析响应数据段
     * 
     * @param data 数据段内容
     * @param functionCode2 功能码2 (0x00表示成功，0x01表示失败)
     */
    public static EelinkClosePumpResponse parse(byte[] data, byte functionCode2) {
        EelinkClosePumpResponse response = new EelinkClosePumpResponse();
        
        // 判断是否成功
        boolean success = (functionCode2 == 0x00);
        response.setSuccess(success);
        
        if (data.length < 1) {
            log.warn("Invalid response data length: {}", data.length);
            response.setSuccess(false);
            return response;
        }
        
        if (success && data.length >= 1) {
            // 成功响应：1字节设备编号
            int deviceCode = data[0] & 0xFF;
            try {
                response.setDevice(EelinkClosePumpRequest.DeviceType.fromCode(deviceCode));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid device code in response: {}", deviceCode);
            }
        } else if (!success && data.length >= 3 && data[0] == (byte) 0xCF && data[1] == (byte) 0xCF) {
            // 错误响应：0xCF 0xCF + 错误码
            response.setErrorCode(data[2]);
        }
        
        log.debug("Parsed closePump response: success={}, device={}, errorCode=0x{}", 
                 success, 
                 response.getDevice() != null ? response.getDevice().name() : "N/A",
                 String.format("%02X", response.getErrorCode() & 0xFF));
        
        return response;
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
    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }
    
    @Override
    public boolean isSuccess() {
        return success;
    }
    
    @Override
    public JsonObject toRpcResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("success", success);
        response.addProperty("command", "closePump");
        
        if (success) {
            if (device != null) {
                response.addProperty("device", device.name());
                response.addProperty("deviceName", device.getDeviceName());
                response.addProperty("deviceCode", device.getCode());
            }
            response.addProperty("message", "关泵开阀成功");
        } else {
            String errorMsg = getErrorMessage(errorCode);
            response.addProperty("error", errorMsg);
            response.addProperty("errorCode", String.format("0x%02X", errorCode & 0xFF));
        }
        
        return response;
    }
    
    /**
     * 获取错误信息
     */
    private String getErrorMessage(byte errorCode) {
        switch (errorCode) {
            case 0x01:
                return "正在控制中";
            case 0x02:
                return "控阀电池没电";
            default:
                return "未知错误";
        }
    }
}

