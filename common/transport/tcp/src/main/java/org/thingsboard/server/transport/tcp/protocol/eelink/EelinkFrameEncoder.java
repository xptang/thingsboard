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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.transport.tcp.protocol.CrcUtil;

/**
 * Eelink（优联时空）设备帧编码器
 * 厂商：北京优联时空科技有限公司
 * 
 * 构建帧格式：
 * [起始段2] [方向1] [帧长度1] [帧代号1] [功能码2] [地址段M] [数据段N] [CRC2] [结束段2]
 */
@Slf4j
public class EelinkFrameEncoder {

    /**
     * 编码响应帧（简化版，用于响应字符串消息）
     * 
     * @param allocator ByteBuf分配器
     * @param data 数据段内容
     * @return 完整的帧
     */
    public static ByteBuf encodeResponse(ByteBufAllocator allocator, byte[] data) {
        // 使用默认参数构建应答帧
        byte frameCode = 0x01;  // 默认帧代号
        byte functionCode1 = 0x00; // WSN地址类型
        byte functionCode2 = 0x10; // 路由1次，立即处理
        byte[] address = {0x00, 0x00}; // 默认地址
        
        return encodeFrame(allocator, EelinkProtocolConfig.DIRECTION_RESPONSE, 
                          frameCode, functionCode1, functionCode2, address, data);
    }
    
    /**
     * 编码完整帧
     */
    public static ByteBuf encodeFrame(ByteBufAllocator allocator,
                                      byte direction,
                                      byte frameCode,
                                      byte functionCode1,
                                      byte functionCode2,
                                      byte[] address,
                                      byte[] data) {
        
        // 计算帧长度：帧代号(1) + 功能码(2) + 地址段(M) + 数据段(N) + CRC(2)
        int frameLength = 1 + 2 + address.length + data.length + 2;
        
        // 分离帧长度的高3位和低8位
        int lengthHighBits = (frameLength >> 8) & 0x07;
        int lengthLowBits = frameLength & 0xFF;
        
        // 计算总长度
        int totalLength = 2 + 1 + 1 + frameLength + 2;
        
        // 分配ByteBuf
        ByteBuf frame = allocator.buffer(totalLength);
        
        // 1. 写入起始段（2字节）
        byte startByte1 = EelinkProtocolConfig.buildStartByte1(lengthHighBits);
        frame.writeByte(startByte1);
        frame.writeByte(EelinkProtocolConfig.HEADER_SECOND);
        
        // 2. 写入方向（1字节）
        frame.writeByte(direction);
        
        // 3. 写入帧长度低8位（1字节）
        frame.writeByte(lengthLowBits);
        
        // 4. 写入帧代号（1字节）
        frame.writeByte(frameCode);
        
        // 5. 写入功能码（2字节）
        frame.writeByte(functionCode1);
        frame.writeByte(functionCode2);
        
        // 6. 写入地址段
        frame.writeBytes(address);
        
        // 7. 写入数据段
        frame.writeBytes(data);
        
        // 8. 计算CRC（从帧长度到数据段）
        byte[] crcData = new byte[1 + 1 + 2 + address.length + data.length];
        int crcIndex = 0;
        crcData[crcIndex++] = (byte) lengthLowBits;
        crcData[crcIndex++] = frameCode;
        crcData[crcIndex++] = functionCode1;
        crcData[crcIndex++] = functionCode2;
        System.arraycopy(address, 0, crcData, crcIndex, address.length);
        crcIndex += address.length;
        System.arraycopy(data, 0, crcData, crcIndex, data.length);
        
        int crc = CrcUtil.calculateCrc16(crcData);
        
        // 9. 写入CRC（2字节，小端序 - LSB first）
        frame.writeByte((byte) (crc & 0xFF));        // 低字节
        frame.writeByte((byte) ((crc >> 8) & 0xFF)); // 高字节
        
        // 10. 写入结束段（2字节）
        frame.writeBytes(EelinkProtocolConfig.FOOTER);
        
        log.debug("Encoded Eelink frame: total={} bytes, frameLength={}, dataLength={}, CRC=0x{}", 
                totalLength, frameLength, data.length, Integer.toHexString(crc));
        
        return frame;
    }
}
