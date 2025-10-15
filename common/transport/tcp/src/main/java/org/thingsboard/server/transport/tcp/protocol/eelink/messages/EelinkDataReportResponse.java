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
import org.thingsboard.server.transport.tcp.protocol.CrcUtil;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig;

/**
 * Eelink数据上报响应（帧代号0x46）
 * 
 * 成功响应格式：
 * - 帧代号: 0x46
 * - 功能码: 0x61 0x00
 * - 数据段: 0x00
 * 
 * 错误响应格式：
 * - 帧代号: 0x46
 * - 功能码: 0x61 0x00
 * - 数据段: 0xCF 0xCF + 错误码
 */
@Data
public class EelinkDataReportResponse {
    
    /** 帧代号 */
    private static final byte FRAME_CODE = 0x46;
    
    /** 功能码1 */
    private static final byte FUNCTION_CODE1 = 0x61;
    
    /** 功能码2 - 响应 */
    private static final byte FUNCTION_CODE2_RESPONSE = 0x00;
    
    /** 错误标志 */
    private static final byte ERROR_FLAG_1 = (byte) 0xCF;
    private static final byte ERROR_FLAG_2 = (byte) 0xCF;
    
    /** 错误码 */
    public static final byte ERROR_NOT_REGISTERED = 0x01;   // 未注册
    public static final byte ERROR_TIME_INVALID = 0x02;     // 时间错误
    public static final byte ERROR_CHANNEL_DATA_INVALID = 0x03;  // 通道数据错误
    
    /** 设备地址 */
    private byte[] address;
    
    /** 是否成功 */
    private boolean success;
    
    /** 错误码（仅当success=false时有效） */
    private byte errorCode;
    
    /**
     * 创建成功响应
     */
    public static EelinkDataReportResponse success(byte[] address) {
        EelinkDataReportResponse response = new EelinkDataReportResponse();
        response.address = address;
        response.success = true;
        return response;
    }
    
    /**
     * 创建错误响应
     */
    public static EelinkDataReportResponse error(byte[] address, byte errorCode) {
        EelinkDataReportResponse response = new EelinkDataReportResponse();
        response.address = address;
        response.success = false;
        response.errorCode = errorCode;
        return response;
    }
    
    /**
     * 编码为字节流
     */
    public ByteBuf encode(ByteBufAllocator allocator) {
        // 计算数据段长度
        int dataLength = success ? 1 : 3;  // 成功1字节(0x00)，失败3字节(0xCF 0xCF + 错误码)
        
        // 计算帧长度 = 帧代号(1) + 功能码(2) + 地址段(4) + 数据段(N) + CRC(2)
        int frameLength = 1 + 2 + address.length + dataLength + 2;
        
        // 提取帧长度高3位和低8位
        int lengthHighBits = (frameLength >> 8) & 0x07;
        int lengthLowBits = frameLength & 0xFF;
        
        // 构造起始段第一字节
        byte startByte1 = (byte) (EelinkProtocolConfig.HEADER_BASE | lengthHighBits);
        
        // 创建缓冲区
        int totalLength = 2 + 1 + 1 + frameLength + 2;  // 起始段(2) + 方向(1) + 帧长度(1) + 帧内容 + 结束段(2)
        ByteBuf buffer = allocator.buffer(totalLength);
        
        // 写入起始段
        buffer.writeByte(startByte1);
        buffer.writeByte(EelinkProtocolConfig.HEADER_SECOND);
        
        // 写入方向（应答帧）
        buffer.writeByte(EelinkProtocolConfig.DIRECTION_RESPONSE);
        
        // 写入帧长度低8位
        buffer.writeByte(lengthLowBits);
        
        // 准备CRC数据
        ByteBuf crcBuffer = allocator.buffer();
        try {
            // CRC计算范围：帧长度低8位 + 帧代号 + 功能码 + 地址段 + 数据段
            crcBuffer.writeByte(lengthLowBits);
            crcBuffer.writeByte(FRAME_CODE);
            crcBuffer.writeByte(FUNCTION_CODE1);
            crcBuffer.writeByte(FUNCTION_CODE2_RESPONSE);
            crcBuffer.writeBytes(address);
            
            // 写入数据段
            if (success) {
                crcBuffer.writeByte(0x00);  // 成功标志
            } else {
                crcBuffer.writeByte(ERROR_FLAG_1);
                crcBuffer.writeByte(ERROR_FLAG_2);
                crcBuffer.writeByte(errorCode);
            }
            
            // 计算CRC
            byte[] crcData = new byte[crcBuffer.readableBytes()];
            crcBuffer.getBytes(0, crcData);
            int crc = CrcUtil.calculateCrc16(crcData);
            
            // 写入帧内容到主缓冲区
            buffer.writeByte(FRAME_CODE);
            buffer.writeByte(FUNCTION_CODE1);
            buffer.writeByte(FUNCTION_CODE2_RESPONSE);
            buffer.writeBytes(address);
            
            // 写入数据段
            if (success) {
                buffer.writeByte(0x00);
            } else {
                buffer.writeByte(ERROR_FLAG_1);
                buffer.writeByte(ERROR_FLAG_2);
                buffer.writeByte(errorCode);
            }
            
            // 写入CRC（小端序）
            buffer.writeByte(crc & 0xFF);
            buffer.writeByte((crc >> 8) & 0xFF);
            
            // 写入结束段
            buffer.writeByte(EelinkProtocolConfig.FOOTER[0]);
            buffer.writeByte(EelinkProtocolConfig.FOOTER[1]);
            
            return buffer;
            
        } finally {
            crcBuffer.release();
        }
    }
    
    /**
     * 获取错误码描述
     */
    public String getErrorDescription() {
        if (success) {
            return "成功";
        }
        
        switch (errorCode) {
            case ERROR_NOT_REGISTERED:
                return "未注册";
            case ERROR_TIME_INVALID:
                return "时间错误";
            case ERROR_CHANNEL_DATA_INVALID:
                return "通道数据错误";
            default:
                return "未知错误(0x" + String.format("%02X", errorCode) + ")";
        }
    }
}

