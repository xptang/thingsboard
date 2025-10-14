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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.transport.tcp.protocol.CrcUtil;

import java.util.List;

/**
 * Eelink（优联时空）设备帧解码器
 * 厂商：北京优联时空科技有限公司
 * 
 * 帧格式：
 * [起始段2] [方向1] [帧长度1] [帧代号1] [功能码2] [地址段M] [数据段N] [CRC2] [结束段2]
 * 
 * 起始段：第一字节低3位为帧长度高3位，去除后为0x88; 第二字节为0xFB
 * 方向：0xFA发起帧, 0xAF应答帧, 0xAA广播帧
 * 帧长度：从帧代号到CRC的长度（不包括起始段、方向、结束段）
 * CRC校验：从帧长度到数据段的CRC16
 * 结束段：固定[0xFC][0xFC]
 */
@Slf4j
public class EelinkFrameDecoder extends ByteToMessageDecoder {

    private static final int MIN_FRAME_LENGTH = 2 + 1 + 1 + 1 + 2 + 2 + 2 + 2; // 13字节最小帧

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 需要至少13字节才能解析最小帧
        if (in.readableBytes() < MIN_FRAME_LENGTH) {
            return;
        }
        
        // 标记当前位置
        in.markReaderIndex();
        
        // 查找起始段
        int startIndex = findStartSegment(in);
        if (startIndex == -1) {
            // 未找到起始段，丢弃所有数据
            in.skipBytes(in.readableBytes());
            return;
        }
        
        // 跳到起始段位置
        in.skipBytes(startIndex);
        in.markReaderIndex();
        
        // 检查是否有足够字节
        if (in.readableBytes() < MIN_FRAME_LENGTH) {
            in.resetReaderIndex();
            return;
        }
        
        try {
            // 读取起始段（2字节）
            byte startByte1 = in.readByte();
            byte startByte2 = in.readByte();
            
            // 验证起始段
            if (!EelinkProtocolConfig.isValidStartByte1(startByte1) || 
                startByte2 != EelinkProtocolConfig.HEADER_SECOND) {
                log.warn("Invalid start segment: 0x{} 0x{}", 
                        Integer.toHexString(startByte1 & 0xFF),
                        Integer.toHexString(startByte2 & 0xFF));
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
            
            // 提取帧长度高3位
            int lengthHighBits = EelinkProtocolConfig.extractLengthHighBits(startByte1);
            
            // 读取方向
            byte direction = in.readByte();
            
            // 读取帧长度低8位
            int lengthLowBits = in.readByte() & 0xFF;
            
            // 计算完整帧长度
            int frameLength = (lengthHighBits << 8) | lengthLowBits;
            
            log.debug("Frame length: {} (high bits: {}, low bits: {})", 
                    frameLength, lengthHighBits, lengthLowBits);
            
            // 验证帧长度合理性
            if (frameLength < 5 || frameLength > 2048) {
                log.warn("Invalid frame length: {}", frameLength);
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
            
            // 计算总帧长度：起始段(2) + 方向(1) + 帧长度(1) + frameLength + 结束段(2)
            int totalFrameLength = 2 + 1 + 1 + frameLength + 2;
            
            // 检查是否有完整帧
            if (in.readableBytes() + 4 < totalFrameLength) { // +4因为已读取了4字节
                in.resetReaderIndex();
                return;
            }
            
            // 读取帧代号
            byte frameCode = in.readByte();
            
            // 读取功能码（2字节）
            byte functionCode1 = in.readByte();
            byte functionCode2 = in.readByte();
            
            // 根据功能码确定地址段长度
            int addressLength = EelinkProtocolConfig.getAddressLength(functionCode1);
            
            // 读取地址段
            byte[] address = new byte[addressLength];
            in.readBytes(address);
            
            // 计算数据段长度
            // frameLength = 帧代号(1) + 功能码(2) + 地址段(M) + 数据段(N) + CRC(2)
            int dataLength = frameLength - 1 - 2 - addressLength - 2;
            
            if (dataLength < 0) {
                log.warn("Invalid data length: {}", dataLength);
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
            
            // 读取数据段
            byte[] data = new byte[dataLength];
            in.readBytes(data);
            
            // 读取CRC（2字节，小端序 - LSB first）
            int receivedCrc = in.readUnsignedByte() | (in.readUnsignedByte() << 8);
            
            // 验证CRC（从帧长度到数据段）
            byte[] crcData = new byte[1 + 1 + 2 + addressLength + dataLength];
            int crcIndex = 0;
            crcData[crcIndex++] = (byte) lengthLowBits;
            crcData[crcIndex++] = frameCode;
            crcData[crcIndex++] = functionCode1;
            crcData[crcIndex++] = functionCode2;
            System.arraycopy(address, 0, crcData, crcIndex, addressLength);
            crcIndex += addressLength;
            System.arraycopy(data, 0, crcData, crcIndex, dataLength);
            
            int calculatedCrc = CrcUtil.calculateCrc16(crcData);
            
            if (calculatedCrc != receivedCrc) {
                log.warn("CRC mismatch - Expected: 0x{}, Calculated: 0x{}", 
                        Integer.toHexString(receivedCrc), Integer.toHexString(calculatedCrc));
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
            
            // 读取并验证结束段
            byte footer1 = in.readByte();
            byte footer2 = in.readByte();
            
            if (footer1 != EelinkProtocolConfig.FOOTER[0] || 
                footer2 != EelinkProtocolConfig.FOOTER[1]) {
                log.warn("Invalid footer: 0x{} 0x{}", 
                        Integer.toHexString(footer1 & 0xFF),
                        Integer.toHexString(footer2 & 0xFF));
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
            
            // 创建帧对象
            EelinkFrame frame = new EelinkFrame();
            frame.setDirection(direction);
            frame.setFrameCode(frameCode);
            frame.setFunctionCode1(functionCode1);
            frame.setFunctionCode2(functionCode2);
            frame.setAddress(address);
            frame.setData(data);
            
            log.info("Decoded Eelink frame: direction=0x{}, frameCode=0x{}, funcCode=0x{} 0x{}, addrLen={}, dataLen={}", 
                    Integer.toHexString(direction & 0xFF),
                    Integer.toHexString(frameCode & 0xFF),
                    Integer.toHexString(functionCode1 & 0xFF),
                    Integer.toHexString(functionCode2 & 0xFF),
                    addressLength,
                    dataLength);
            
            out.add(frame);
            
        } catch (Exception e) {
            log.error("Error decoding frame", e);
            in.resetReaderIndex();
            in.skipBytes(1);
        }
    }
    
    /**
     * 查找起始段
     */
    private int findStartSegment(ByteBuf in) {
        int readerIndex = in.readerIndex();
        int writerIndex = in.writerIndex();
        
        for (int i = readerIndex; i < writerIndex - 1; i++) {
            byte b1 = in.getByte(i);
            byte b2 = in.getByte(i + 1);
            
            if (EelinkProtocolConfig.isValidStartByte1(b1) && 
                b2 == EelinkProtocolConfig.HEADER_SECOND) {
                return i - readerIndex;
            }
        }
        
        return -1;
    }
}

