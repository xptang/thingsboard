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
package org.thingsboard.server.transport.tcp.protocol.eelink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.server.transport.tcp.TbTcpTransportComponent;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkCommandRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkCommandResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkOpenPumpRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkOpenPumpResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkClosePumpRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkClosePumpResponse;

/**
 * Eelink RPC命令映射器
 * 负责ThingsBoard RPC方法名和优联私有协议指令之间的双向映射
 */
@Component
@TbTcpTransportComponent
@Slf4j
public class EelinkRpcCommandMapper {
    
    /**
     * ⭐ 核心方法1：根据RPC方法名创建对应的Eelink命令请求
     * 
     * @param method RPC方法名
     * @param paramsJson RPC参数JSON字符串
     * @param requestId RPC请求ID
     * @return Eelink命令请求对象
     * @throws UnsupportedCommandException 不支持的命令
     */
    public EelinkCommandRequest createRequest(String method, String paramsJson, int requestId) 
            throws UnsupportedCommandException {
        
        JsonObject params = parseParams(paramsJson);
        
        log.info("Creating Eelink command: method={}, params={}, requestId={}", method, paramsJson, requestId);
        
        switch (method) {
            case "openPump":
                return EelinkOpenPumpRequest.fromRpc(requestId, params);
                
            case "closePump":
                return EelinkClosePumpRequest.fromRpc(requestId, params);
                
            // TODO: 添加更多命令映射
            // case "setValveOpening":
            //     return EelinkSetValveOpeningRequest.fromRpc(requestId, params);
                
            default:
                throw new UnsupportedCommandException("Unsupported RPC method: " + method);
        }
    }
    
    /**
     * ⭐ 核心方法2：根据帧代号解析对应的Eelink命令响应
     * 
     * @param frameCode 帧代号
     * @param data 数据段
     * @param functionCode2 功能码2（用于判断成功/失败）
     * @return Eelink命令响应对象
     * @throws UnsupportedCommandException 不支持的帧代号
     */
    public EelinkCommandResponse parseResponse(byte frameCode, byte[] data, byte functionCode2) 
            throws UnsupportedCommandException {
        
        log.debug("Parsing Eelink response: frameCode=0x{}, functionCode2=0x{}, dataLen={}", 
                 String.format("%02X", frameCode & 0xFF),
                 String.format("%02X", functionCode2 & 0xFF),
                 data.length);
        
        switch (frameCode) {
            case (byte) 0x81:  // 开泵关阀响应
                return EelinkOpenPumpResponse.parse(data, functionCode2);
                
            case 0x01:  // 关泵开阀响应
                return EelinkClosePumpResponse.parse(data, functionCode2);
                
            // TODO: 添加更多响应解析
            // case (byte) 0xF4:  // 阀门开度控制响应
            //     return EelinkSetValveOpeningResponse.parse(data, functionCode2);
                
            default:
                throw new UnsupportedCommandException("Unsupported response frame code: 0x" 
                        + String.format("%02X", frameCode & 0xFF));
        }
    }
    
    /**
     * 检查方法是否支持
     */
    public boolean isMethodSupported(String method) {
        switch (method) {
            case "openPump":
            case "closePump":
                return true;
            default:
                return false;
        }
    }
    
    /**
     * 解析参数JSON
     */
    private JsonObject parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isEmpty() || "{}".equals(paramsJson) || "null".equals(paramsJson)) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(paramsJson).getAsJsonObject();
        } catch (Exception e) {
            log.warn("Failed to parse params JSON: {}", paramsJson, e);
            return new JsonObject();
        }
    }
    
    /**
     * 不支持的命令异常
     */
    public static class UnsupportedCommandException extends Exception {
        public UnsupportedCommandException(String message) {
            super(message);
        }
    }
}

