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
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Eelink数据上报请求（帧代号0x46）
 * 
 * 数据段格式：
 * - 非加密标识（1字节）：0x00
 * - 数据包数量（1字节）：N（0表示1个）
 * - 数据包1...N
 */
@Data
@Slf4j
public class EelinkDataReportRequest {
    
    /** 最小数据段长度 = 加密标识(1) + 数据包数量(1) = 2字节 */
    private static final int MIN_DATA_LENGTH = 2;
    
    /** 非加密标识 */
    private boolean encrypted;
    
    /** 数据包数量 */
    private int packetCount;
    
    /** 数据包列表 */
    private List<EelinkDataPacket> dataPackets;
    
    /**
     * 从字节数组解析数据上报请求
     * 
     * @param data 数据段字节数组
     * @return 解析后的数据上报请求对象
     * @throws IllegalArgumentException 如果数据长度不正确
     */
    public static EelinkDataReportRequest parse(byte[] data) {
        if (data == null || data.length < MIN_DATA_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Invalid data report request length: expected at least %d, got %d", 
                    MIN_DATA_LENGTH, data == null ? 0 : data.length));
        }
        
        EelinkDataReportRequest request = new EelinkDataReportRequest();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        
        // 解析非加密标识（1字节）
        byte encryptedFlag = buffer.get();
        request.encrypted = (encryptedFlag != 0x00);
        
        // 解析数据包数量（1字节，0也表示1个）
        int packetCountByte = buffer.get() & 0xFF;
        request.packetCount = (packetCountByte == 0) ? 1 : packetCountByte;
        
        // 解析数据包
        request.dataPackets = new ArrayList<>(request.packetCount);
        for (int i = 0; i < request.packetCount; i++) {
            try {
                EelinkDataPacket packet = EelinkDataPacket.parse(buffer);
                request.dataPackets.add(packet);
                log.debug("Parsed data packet {}: time={}, channels={}, addr={}, storage={}/{}", 
                        i + 1, 
                        packet.getCollectTime(),
                        packet.getChannelCount(),
                        packet.getDataAddressString(),
                        packet.getStorageTypeName(),
                        packet.getStorageSequence());
            } catch (Exception e) {
                log.error("Failed to parse data packet {}", i + 1, e);
                throw new IllegalArgumentException("Failed to parse data packet " + (i + 1), e);
            }
        }
        
        return request;
    }
    
    /**
     * 获取总通道数量（所有数据包的通道数量之和）
     */
    public int getTotalChannelCount() {
        return dataPackets.stream()
                .mapToInt(EelinkDataPacket::getChannelCount)
                .sum();
    }
    
    /**
     * 获取第一个数据包（如果存在）
     */
    public EelinkDataPacket getFirstPacket() {
        return dataPackets.isEmpty() ? null : dataPackets.get(0);
    }
    
    /**
     * 检查是否为加密数据
     */
    public boolean isEncrypted() {
        return encrypted;
    }
}

