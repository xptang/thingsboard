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

import lombok.Data;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Eelink数据包
 * 
 * 数据包格式：
 * - 采集时间（6字节）：年月日时分秒
 * - 通道数量（1字节）
 * - 数据地址（4字节）：Float，低位在前
 * - 存储信息（4字节）：Float，高位在前（存储类型*1000000 + 存储序号）
 * - 通道数据（4*N字节）：浮点数，低位在前
 */
@Data
public class EelinkDataPacket {
    
    /** 最小数据包长度 = 时间(6) + 通道数(1) + 数据地址(4) + 存储信息(4) = 15字节 */
    private static final int MIN_PACKET_LENGTH = 15;
    
    /** 采集时间 */
    private LocalDateTime collectTime;
    
    /** 通道数量 */
    private int channelCount;
    
    /** 数据地址 */
    private byte[] dataAddress;
    
    /** 存储类型（0-定时存储，1-加密存储） */
    private int storageType;
    
    /** 存储序号 */
    private int storageSequence;
    
    /** 通道数据 */
    private List<Float> channelData;
    
    /**
     * 从ByteBuffer解析数据包
     * 
     * @param buffer 字节缓冲区
     * @return 解析后的数据包对象
     * @throws IllegalArgumentException 如果数据不足
     */
    public static EelinkDataPacket parse(ByteBuffer buffer) {
        if (buffer.remaining() < MIN_PACKET_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Invalid data packet length: expected at least %d, got %d", 
                    MIN_PACKET_LENGTH, buffer.remaining()));
        }
        
        EelinkDataPacket packet = new EelinkDataPacket();
        
        // 解析采集时间（6字节）
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int year = 2000 + (buffer.get() & 0xFF);
        int month = buffer.get() & 0xFF;
        int day = buffer.get() & 0xFF;
        int hour = buffer.get() & 0xFF;
        int minute = buffer.get() & 0xFF;
        int second = buffer.get() & 0xFF;
        packet.collectTime = LocalDateTime.of(year, month, day, hour, minute, second);
        
        // 解析通道数量（1字节）
        packet.channelCount = buffer.get() & 0xFF;
        
        // 解析数据地址（4字节，低位在前）
        packet.dataAddress = new byte[4];
        buffer.get(packet.dataAddress);
        
        // 解析存储信息（4字节，高位在前，Float格式）
        buffer.order(ByteOrder.BIG_ENDIAN);
        float storageInfo = buffer.getFloat();
        
        // 解析存储类型和序号：存储类型 * 1000000 + 存储序号
        int storageInfoInt = (int) storageInfo;
        packet.storageType = storageInfoInt / 1000000;
        packet.storageSequence = storageInfoInt % 1000000;
        
        // 解析通道数据（4*N字节，浮点数，低位在前）
        // 远程测控终端内容定义（最多11通道）：
        // 0-压力, 1-水位, 2-阀门电压, 3-电池电压, 4-开关状态, 5-设备信息,
        // 6-瞬时流量, 7-累计流量, 8-阀门开度, 9-阀门开度2, 10-阀门开度3
        // 
        // 泵房通道定义（最多15通道）：
        // 0-压力, 1-水位, 2-阀门电压, 3-开关状态, 4-设备信息, 5-瞬时流量, 6-累计流量,
        // 7-A相电压, 8-B相电压, 9-C相电压, 10-A相电流, 11-B相电流, 12-C相电流,
        // 13-电量, 14-电功率
        //
        // 根据实际通道数量动态解析
        packet.channelData = new ArrayList<>(packet.channelCount);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < packet.channelCount; i++) {
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException(
                        String.format("Insufficient data for channel %d: expected 4 bytes, got %d", 
                        i, buffer.remaining()));
            }
            packet.channelData.add(buffer.getFloat());
        }
        
        return packet;
    }
    
    /**
     * 获取数据地址字符串
     */
    public String getDataAddressString() {
        return EelinkProtocolConfig.bytesToHex(dataAddress);
    }
    
    /**
     * 获取存储类型名称
     */
    public String getStorageTypeName() {
        return storageType == 0 ? "定时存储" : "加密存储";
    }
    
    /**
     * 获取指定索引的通道数据，如果不存在返回null
     */
    public Float getChannelData(int index) {
        if (index >= 0 && index < channelData.size()) {
            return channelData.get(index);
        }
        return null;
    }
    
    /**
     * 计算数据包的长度
     */
    public int getPacketLength() {
        return MIN_PACKET_LENGTH + (channelCount * 4);
    }
}

