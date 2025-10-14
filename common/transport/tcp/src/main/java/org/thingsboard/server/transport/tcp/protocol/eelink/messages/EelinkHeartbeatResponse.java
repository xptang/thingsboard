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

import java.time.LocalDateTime;

/**
 * Eelink设备心跳响应
 * 帧代号：0x43
 * 
 * 响应类型：
 * 1. 链路心跳响应: 2字节 - 心跳类型(1) + 临时连接(1)
 * 2. 状态心跳响应: 可变长度 - 根据同步标识决定
 */
@Data
public class EelinkHeartbeatResponse {
    
    // 错误标志
    public static final byte[] ERROR_FLAG = {(byte)0xCF, (byte)0xCF};
    
    // 错误代码
    public static final byte ERROR_NOT_REGISTERED = 0x01;  // 未注册
    public static final byte ERROR_DATA_INVALID = 0x02;    // 数据内容有错误
    
    // 临时连接标志
    public static final byte TEMP_CONN_NORMAL = 0x00;      // 正常返回
    public static final byte TEMP_CONN_CANCEL = (byte)0xA5; // 取消临时链接
    
    // 同步标识位
    public static final int SYNC_CONNECTION_MODE = 0x0001;  // Bit0: 连接方式同步
    public static final int SYNC_TIME = 0x0002;             // Bit1: 时间同步
    public static final int SYNC_REPORT_PERIOD = 0x0004;    // Bit2: 上报周期同步
    public static final int SYNC_COLLECT_PERIOD = 0x0008;   // Bit3: 采集周期同步
    public static final int SYNC_STORAGE_PERIOD = 0x0010;   // Bit4: 存储周期同步
    public static final int SYNC_CLEAR_HISTORY = 0x0020;    // Bit5: 清除历史数据
    public static final int SYNC_REBOOT = 0x0040;           // Bit6: 重启
    public static final int SYNC_TEMP_ONLINE = 0x0080;      // Bit7: 临时在线
    
    // 功能码（响应帧固定值）
    public static final byte FUNCTION_CODE_2 = 0x00;        // 应答帧功能码第2字节固定为0x00
    
    private byte[] address;           // 设备地址
    private byte functionCode1;       // 功能码第1字节
    private boolean success;          // 是否成功
    private Byte errorCode;           // 错误代码（失败时）
    
    // 链路心跳响应字段
    private Byte tempConnection;      // 临时连接标志
    
    // 状态心跳响应字段
    private Integer syncFlags;        // 同步标识
    private Byte connectionMode;      // 连接方式
    private LocalDateTime currentTime; // 平台当前时间
    private Integer reportPeriod;     // 上报周期
    private Integer collectPeriod;    // 采集周期
    private Integer storagePeriod;    // 存储周期
    
    /**
     * 创建链路心跳成功响应
     */
    public static EelinkHeartbeatResponse linkSuccess(byte[] address, byte functionCode1) {
        EelinkHeartbeatResponse response = new EelinkHeartbeatResponse();
        response.address = address;
        response.functionCode1 = functionCode1;
        response.success = true;
        response.tempConnection = TEMP_CONN_NORMAL;
        return response;
    }
    
    /**
     * 创建状态心跳成功响应（无同步）
     */
    public static EelinkHeartbeatResponse statusSuccess(byte[] address, byte functionCode1) {
        EelinkHeartbeatResponse response = new EelinkHeartbeatResponse();
        response.address = address;
        response.functionCode1 = functionCode1;
        response.success = true;
        response.syncFlags = 0;  // 无同步
        return response;
    }
    
    /**
     * 创建状态心跳成功响应（带时间同步）
     */
    public static EelinkHeartbeatResponse statusSuccessWithTimeSync(byte[] address, byte functionCode1, 
                                                                     LocalDateTime time) {
        EelinkHeartbeatResponse response = new EelinkHeartbeatResponse();
        response.address = address;
        response.functionCode1 = functionCode1;
        response.success = true;
        response.syncFlags = 0;
        response.currentTime = time;
        return response;
    }
    
    /**
     * 创建错误响应
     */
    public static EelinkHeartbeatResponse error(byte[] address, byte functionCode1, byte errorCode) {
        EelinkHeartbeatResponse response = new EelinkHeartbeatResponse();
        response.address = address;
        response.functionCode1 = functionCode1;
        response.success = false;
        response.errorCode = errorCode;
        return response;
    }
    
    /**
     * 编码为ByteBuf
     */
    public ByteBuf encode(ByteBufAllocator allocator) {
        byte[] data;
        
        if (!success) {
            // 错误响应: ERROR_FLAG + 错误代码
            data = new byte[3];
            data[0] = ERROR_FLAG[0];
            data[1] = ERROR_FLAG[1];
            data[2] = errorCode;
        } else if (tempConnection != null) {
            // 链路心跳响应
            data = new byte[2];
            data[0] = EelinkHeartbeatRequest.TYPE_LINK;
            data[1] = tempConnection;
        } else {
            // 状态心跳响应
            data = buildStatusResponseData();
        }
        
        return EelinkFrameEncoder.encodeFrame(allocator, 
                EelinkProtocolConfig.DIRECTION_RESPONSE,
                EelinkHeartbeatRequest.FRAME_CODE,
                functionCode1,
                FUNCTION_CODE_2,  // 应答帧功能码第2字节固定为0x00
                address,
                data);
    }
    
    /**
     * 构建状态心跳响应数据
     */
    private byte[] buildStatusResponseData() {
        // 计算数据长度: 心跳类型(1) + 保留(1) + 同步标识(2) + 可选字段
        int dataLength = 4;
        
        if ((syncFlags & SYNC_CONNECTION_MODE) != 0) {
            dataLength += 1;
        }
        if ((syncFlags & SYNC_TIME) != 0) {
            dataLength += 6;
        }
        if ((syncFlags & SYNC_REPORT_PERIOD) != 0) {
            dataLength += 4;
        }
        if ((syncFlags & SYNC_COLLECT_PERIOD) != 0) {
            dataLength += 4;
        }
        if ((syncFlags & SYNC_STORAGE_PERIOD) != 0) {
            dataLength += 4;
        }
        
        byte[] data = new byte[dataLength];
        int offset = 0;
        
        // 心跳类型
        data[offset++] = EelinkHeartbeatRequest.TYPE_STATUS;
        
        // 保留字节
        data[offset++] = 0x00;
        
        // 同步标识（2字节，大端序）
        data[offset++] = (byte)((syncFlags >> 8) & 0xFF);
        data[offset++] = (byte)(syncFlags & 0xFF);
        
        // 连接方式
        if ((syncFlags & SYNC_CONNECTION_MODE) != 0 && connectionMode != null) {
            data[offset++] = connectionMode;
        }
        
        // 时间
        if ((syncFlags & SYNC_TIME) != 0 && currentTime != null) {
            data[offset++] = (byte)(currentTime.getYear() - 2000);
            data[offset++] = (byte)currentTime.getMonthValue();
            data[offset++] = (byte)currentTime.getDayOfMonth();
            data[offset++] = (byte)currentTime.getHour();
            data[offset++] = (byte)currentTime.getMinute();
            data[offset++] = (byte)currentTime.getSecond();
        }
        
        // 上报周期
        if ((syncFlags & SYNC_REPORT_PERIOD) != 0 && reportPeriod != null) {
            writeInt32BigEndian(data, offset, reportPeriod);
            offset += 4;
        }
        
        // 采集周期
        if ((syncFlags & SYNC_COLLECT_PERIOD) != 0 && collectPeriod != null) {
            writeInt32BigEndian(data, offset, collectPeriod);
            offset += 4;
        }
        
        // 存储周期
        if ((syncFlags & SYNC_STORAGE_PERIOD) != 0 && storagePeriod != null) {
            writeInt32BigEndian(data, offset, storagePeriod);
        }
        
        return data;
    }
    
    /**
     * 写入32位整数（大端序）
     */
    private void writeInt32BigEndian(byte[] data, int offset, int value) {
        data[offset] = (byte)((value >> 24) & 0xFF);
        data[offset + 1] = (byte)((value >> 16) & 0xFF);
        data[offset + 2] = (byte)((value >> 8) & 0xFF);
        data[offset + 3] = (byte)(value & 0xFF);
    }
}

