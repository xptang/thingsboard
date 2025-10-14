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

import java.time.LocalDateTime;

/**
 * Eelink设备心跳请求
 * 帧代号：0x43
 * 
 * 心跳类型：
 * 1. 链路心跳 (0x01): 2字节 - 心跳类型(1) + 保留(1)
 * 2. 状态心跳 (0x02): 34字节 - 包含设备状态信息
 */
@Data
@Slf4j
public class EelinkHeartbeatRequest {
    
    public static final byte FRAME_CODE = 0x43;
    public static final byte TYPE_LINK = 0x01;      // 链路心跳
    public static final byte TYPE_STATUS = 0x02;    // 状态心跳
    
    public static final int MIN_DATA_LENGTH = 2;    // 最小数据长度（链路心跳）
    public static final int STATUS_DATA_LENGTH = 34; // 状态心跳数据长度 (1+1+1+6+4+4+4+1+4+4+4)
    
    private byte heartbeatType;  // 心跳类型
    
    // 状态心跳特有字段
    private Byte connectionMode;      // 连接方式: 1=长连接, 2=短连接, 3=无连接
    private LocalDateTime deviceTime; // 设备时间
    private Integer reportPeriod;     // 上报周期(秒)
    private Integer collectPeriod;    // 采集周期(秒)
    private Integer storagePeriod;    // 存储周期(秒)
    private Byte signalStrength;      // 信号强度
    private Integer storedDataCount;  // 存储数据条数
    private Integer unreportedCount;  // 未报数据条数
    private Float batteryVoltage;     // 电池电压
    
    /**
     * 从byte[]解析心跳请求
     */
    public static EelinkHeartbeatRequest parse(byte[] data) {
        if (data == null || data.length < MIN_DATA_LENGTH) {
            throw new IllegalArgumentException("Invalid heartbeat request data length: " + 
                    (data == null ? 0 : data.length));
        }
        
        EelinkHeartbeatRequest request = new EelinkHeartbeatRequest();
        
        // 读取心跳类型
        request.heartbeatType = data[0];
        
        if (request.heartbeatType == TYPE_LINK) {
            // 链路心跳，只有2字节
            log.info("Parsed Eelink link heartbeat request");
        } else if (request.heartbeatType == TYPE_STATUS) {
            // 状态心跳，需要34字节
            if (data.length < STATUS_DATA_LENGTH) {
                throw new IllegalArgumentException("Invalid status heartbeat data length: " + data.length);
            }
            
            int offset = 1; // 跳过心跳类型
            
            // 跳过保留字节
            offset += 1;
            
            // 连接方式（1字节）
            request.connectionMode = data[offset++];
            
            // 设备时间（6字节：年月日时分秒）
            int year = 2000 + (data[offset++] & 0xFF);
            int month = data[offset++] & 0xFF;
            int day = data[offset++] & 0xFF;
            int hour = data[offset++] & 0xFF;
            int minute = data[offset++] & 0xFF;
            int second = data[offset++] & 0xFF;
            request.deviceTime = LocalDateTime.of(year, month, day, hour, minute, second);
            
            // 上报周期（4字节，大端序）
            request.reportPeriod = readInt32BigEndian(data, offset);
            offset += 4;
            
            // 采集周期（4字节，大端序）
            request.collectPeriod = readInt32BigEndian(data, offset);
            offset += 4;
            
            // 存储周期（4字节，大端序）
            request.storagePeriod = readInt32BigEndian(data, offset);
            offset += 4;
            
            // 信号强度（1字节）
            request.signalStrength = data[offset++];
            
            // 存储数据条数（4字节，大端序）
            request.storedDataCount = readInt32BigEndian(data, offset);
            offset += 4;
            
            // 未报数据条数（4字节，大端序）
            request.unreportedCount = readInt32BigEndian(data, offset);
            offset += 4;
            
            // 电池电压（4字节，浮点数，大端序）
            request.batteryVoltage = readFloatBigEndian(data, offset);
            
            log.info("Parsed Eelink status heartbeat: mode={}, time={}, report={}s, collect={}s, " +
                    "storage={}s, signal={}, stored={}, unreported={}, battery={}V",
                    request.connectionMode, request.deviceTime, request.reportPeriod, 
                    request.collectPeriod, request.storagePeriod, request.signalStrength & 0xFF,
                    request.storedDataCount, request.unreportedCount, request.batteryVoltage);
        } else {
            throw new IllegalArgumentException("Unknown heartbeat type: 0x" + 
                    String.format("%02X", request.heartbeatType));
        }
        
        return request;
    }
    
    /**
     * 从ByteBuf解析心跳请求
     */
    public static EelinkHeartbeatRequest parse(ByteBuf data) {
        int length = data.readableBytes();
        byte[] bytes = new byte[length];
        data.readBytes(bytes);
        return parse(bytes);
    }
    
    /**
     * 判断是否为链路心跳
     */
    public boolean isLinkHeartbeat() {
        return heartbeatType == TYPE_LINK;
    }
    
    /**
     * 判断是否为状态心跳
     */
    public boolean isStatusHeartbeat() {
        return heartbeatType == TYPE_STATUS;
    }
    
    /**
     * 读取32位整数（大端序）
     */
    private static int readInt32BigEndian(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }
    
    /**
     * 读取浮点数（大端序）
     */
    private static float readFloatBigEndian(byte[] data, int offset) {
        int bits = readInt32BigEndian(data, offset);
        return Float.intBitsToFloat(bits);
    }
}

