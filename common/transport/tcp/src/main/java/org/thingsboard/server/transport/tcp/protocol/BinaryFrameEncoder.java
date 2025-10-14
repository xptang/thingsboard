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
package org.thingsboard.server.transport.tcp.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

/**
 * Binary Frame Encoder
 * 
 * Encodes payload into binary frame format:
 * [Header] [Length] [Payload] [CRC] [Footer]
 */
@Slf4j
public class BinaryFrameEncoder {

    private final BinaryProtocolConfig config;
    
    public BinaryFrameEncoder(BinaryProtocolConfig config) {
        this.config = config;
    }
    
    /**
     * Encode payload into binary frame
     */
    public ByteBuf encode(ByteBufAllocator allocator, byte[] payload) {
        int payloadLength = payload.length;
        int totalLength = config.getHeader().length + config.getLengthBytes() + 
                         payloadLength + config.getCrcBytes() + config.getFooter().length;
        
        ByteBuf frame = allocator.buffer(totalLength);
        
        // Write header
        frame.writeBytes(config.getHeader());
        
        // Write length
        writeLength(frame, payloadLength);
        
        // Write payload
        frame.writeBytes(payload);
        
        // Calculate and write CRC
        if (config.getCrcBytes() > 0) {
            writeCrc(frame, payload);
        }
        
        // Write footer
        frame.writeBytes(config.getFooter());
        
        log.debug("Encoded binary frame: total={} bytes, payload={} bytes", totalLength, payloadLength);
        
        return frame;
    }
    
    /**
     * Write length field
     */
    private void writeLength(ByteBuf frame, int length) {
        if (config.isBigEndian()) {
            // Big-endian
            for (int i = config.getLengthBytes() - 1; i >= 0; i--) {
                frame.writeByte((length >> (i * 8)) & 0xFF);
            }
        } else {
            // Little-endian
            for (int i = 0; i < config.getLengthBytes(); i++) {
                frame.writeByte((length >> (i * 8)) & 0xFF);
            }
        }
    }
    
    /**
     * Calculate and write CRC
     */
    private void writeCrc(ByteBuf frame, byte[] payload) {
        long crc;
        
        if (config.getCrcBytes() == 2) {
            crc = CrcUtil.calculateCrc16(payload);
        } else {
            crc = CrcUtil.calculateCrc32(payload);
        }
        
        if (config.isCrcBigEndian()) {
            // Big-endian
            for (int i = config.getCrcBytes() - 1; i >= 0; i--) {
                frame.writeByte((int)((crc >> (i * 8)) & 0xFF));
            }
        } else {
            // Little-endian
            for (int i = 0; i < config.getCrcBytes(); i++) {
                frame.writeByte((int)((crc >> (i * 8)) & 0xFF));
            }
        }
        
        log.debug("CRC calculated: 0x{}", Long.toHexString(crc));
    }
}

