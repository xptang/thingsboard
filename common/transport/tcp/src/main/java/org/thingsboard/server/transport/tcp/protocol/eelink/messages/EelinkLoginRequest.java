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
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteOrder;

import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig;

/**
 * Eelink设备登陆请求
 * 帧代号：0x41
 * 
 * 数据段结构（28字节）：
 * - CCID: 10字节
 * - IMEI: 8字节
 * - 复位原因+地址信息: 1字节
 * - 软件版本: 4字节（浮点数，高位在前）
 * - 硬件版本: 4字节（浮点数，高位在前）
 * - 复位次数: 1字节
 */
@Data
@Slf4j
public class EelinkLoginRequest {
    
    public static final byte FRAME_CODE = 0x41;
    public static final int DATA_LENGTH = 28;  // 10+8+1+4+4+1
    
    private byte[] ccid;           // 10字节 - SIM卡CCID
    private byte[] imei;           // 8字节 - 设备IMEI
    private byte resetInfo;        // 1字节 - 复位原因+高低位地址
    private float softwareVersion; // 4字节 - 软件版本
    private float hardwareVersion; // 4字节 - 硬件版本
    private byte resetCount;       // 1字节 - 复位次数
    
    /**
     * 从byte[]解析登陆请求
     */
    public static EelinkLoginRequest parse(byte[] data) {
        if (data == null || data.length < DATA_LENGTH) {
            throw new IllegalArgumentException("Invalid login request data length: " + 
                    (data == null ? 0 : data.length));
        }
        
        EelinkLoginRequest request = new EelinkLoginRequest();
        int offset = 0;
        
        // 读取CCID（10字节）
        request.ccid = new byte[10];
        System.arraycopy(data, offset, request.ccid, 0, 10);
        offset += 10;
        
        // 读取IMEI（8字节）
        request.imei = new byte[8];
        System.arraycopy(data, offset, request.imei, 0, 8);
        offset += 8;
        
        // 读取复位信息（1字节）
        request.resetInfo = data[offset++];
        
        // 读取软件版本（4字节，浮点数，高位在前/大端序）
        int softwareVersionBits = ((data[offset] & 0xFF) << 24) | 
                                  ((data[offset + 1] & 0xFF) << 16) | 
                                  ((data[offset + 2] & 0xFF) << 8) | 
                                  (data[offset + 3] & 0xFF);
        request.softwareVersion = Float.intBitsToFloat(softwareVersionBits);
        offset += 4;
        
        // 读取硬件版本（4字节，浮点数，高位在前/大端序）
        int hardwareVersionBits = ((data[offset] & 0xFF) << 24) | 
                                  ((data[offset + 1] & 0xFF) << 16) | 
                                  ((data[offset + 2] & 0xFF) << 8) | 
                                  (data[offset + 3] & 0xFF);
        request.hardwareVersion = Float.intBitsToFloat(hardwareVersionBits);
        offset += 4;
        
        // 读取复位次数（1字节）
        request.resetCount = data[offset];
        
        log.info("Parsed Eelink login request: CCID={}, IMEI={}, SW={}, HW={}, ResetCount={}", 
                request.getCcidString(),
                request.getImeiString(), 
                request.softwareVersion, 
                request.hardwareVersion, 
                request.resetCount & 0xFF);
        
        return request;
    }
    
    /**
     * 从ByteBuf解析登陆请求（保留兼容性）
     */
    public static EelinkLoginRequest parse(ByteBuf data) {
        if (data.readableBytes() < DATA_LENGTH) {
            throw new IllegalArgumentException("Invalid login request data length: " + data.readableBytes());
        }
        
        byte[] bytes = new byte[DATA_LENGTH];
        data.readBytes(bytes);
        return parse(bytes);
    }
    
    /**
     * 获取CCID字符串
     */
    public String getCcidString() {
        return EelinkProtocolConfig.bytesToHex(ccid);
    }
    
    /**
     * 获取IMEI字符串
     */
    public String getImeiString() {
        return EelinkProtocolConfig.bytesToHex(imei);
    }
    
    /**
     * 获取复位原因
     */
    public int getResetReason() {
        return (resetInfo >> 4) & 0x0F;
    }
    
    /**
     * 获取地址类型标识
     */
    public int getAddressType() {
        return resetInfo & 0x0F;
    }

}

