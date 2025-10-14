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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Binary Frame Decoder
 * 
 * Frame Format: [Header] [Length] [Payload] [CRC] [Footer]
 * 
 * Example with default config:
 * - Header: 0xAA55 (2 bytes)
 * - Length: payload length (2 bytes, big-endian)
 * - Payload: actual data
 * - CRC: CRC16 of payload (2 bytes, big-endian)
 * - Footer: 0x55AA (2 bytes)
 */
@Slf4j
public class BinaryFrameDecoder extends ByteToMessageDecoder {

    private final BinaryProtocolConfig config;
    
    public BinaryFrameDecoder(BinaryProtocolConfig config) {
        this.config = config;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Mark the current reader index
        in.markReaderIndex();
        
        // Check if we have enough bytes for the minimum frame
        int minFrameLength = config.getHeader().length + config.getLengthBytes() + 
                            config.getCrcBytes() + config.getFooter().length;
        
        if (in.readableBytes() < minFrameLength) {
            in.resetReaderIndex();
            return; // Wait for more data
        }
        
        // Find header
        int headerIndex = findHeader(in);
        if (headerIndex == -1) {
            // Header not found, discard all bytes and wait for more data
            in.skipBytes(in.readableBytes());
            return;
        }
        
        // Skip to header position
        in.skipBytes(headerIndex);
        in.markReaderIndex();
        
        // Check if we have enough bytes after header
        if (in.readableBytes() < minFrameLength) {
            in.resetReaderIndex();
            return;
        }
        
        // Skip header
        in.skipBytes(config.getHeader().length);
        
        // Read payload length
        int payloadLength = readLength(in);
        
        // Validate payload length
        if (payloadLength < 0 || payloadLength > 1024 * 1024) { // Max 1MB
            log.warn("Invalid payload length: {}, discarding frame", payloadLength);
            in.resetReaderIndex();
            in.skipBytes(1); // Skip one byte and try again
            return;
        }
        
        // Check if we have the complete frame
        int totalFrameLength = config.getHeader().length + config.getLengthBytes() + 
                              payloadLength + config.getCrcBytes() + config.getFooter().length;
        
        if (in.readableBytes() + config.getHeader().length + config.getLengthBytes() < totalFrameLength) {
            in.resetReaderIndex();
            return; // Wait for more data
        }
        
        // Read payload
        byte[] payload = new byte[payloadLength];
        in.readBytes(payload);
        
        // Read and verify CRC
        if (config.getCrcBytes() > 0) {
            if (!verifyCrc(payload, in)) {
                log.warn("CRC verification failed, discarding frame");
                in.resetReaderIndex();
                in.skipBytes(1);
                return;
            }
        }
        
        // Read and verify footer
        byte[] footer = new byte[config.getFooter().length];
        in.readBytes(footer);
        
        if (!Arrays.equals(footer, config.getFooter())) {
            log.warn("Invalid footer, discarding frame");
            in.resetReaderIndex();
            in.skipBytes(1);
            return;
        }
        
        // Frame is valid, create ByteBuf with payload
        ByteBuf payloadBuf = ctx.alloc().buffer(payloadLength);
        payloadBuf.writeBytes(payload);
        out.add(payloadBuf);
        
        log.debug("Successfully decoded binary frame, payload length: {}", payloadLength);
    }
    
    /**
     * Find header in the buffer
     * Returns the index of the header, or -1 if not found
     */
    private int findHeader(ByteBuf in) {
        int readerIndex = in.readerIndex();
        byte[] header = config.getHeader();
        
        for (int i = readerIndex; i <= in.writerIndex() - header.length; i++) {
            boolean found = true;
            for (int j = 0; j < header.length; j++) {
                if (in.getByte(i + j) != header[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i - readerIndex;
            }
        }
        return -1;
    }
    
    /**
     * Read length field from buffer
     */
    private int readLength(ByteBuf in) {
        int length = 0;
        
        if (config.isBigEndian()) {
            // Big-endian
            for (int i = 0; i < config.getLengthBytes(); i++) {
                length = (length << 8) | (in.readByte() & 0xFF);
            }
        } else {
            // Little-endian
            for (int i = 0; i < config.getLengthBytes(); i++) {
                length = length | ((in.readByte() & 0xFF) << (i * 8));
            }
        }
        
        return length;
    }
    
    /**
     * Verify CRC
     */
    private boolean verifyCrc(byte[] payload, ByteBuf in) {
        if (config.getCrcBytes() == 0) {
            return true;
        }
        
        long expectedCrc = readCrc(in);
        long calculatedCrc;
        
        if (config.getCrcBytes() == 2) {
            calculatedCrc = CrcUtil.calculateCrc16(payload);
        } else {
            calculatedCrc = CrcUtil.calculateCrc32(payload);
        }
        
        boolean valid = calculatedCrc == expectedCrc;
        
        if (!valid) {
            log.warn("CRC mismatch - Expected: 0x{}, Calculated: 0x{}", 
                    Long.toHexString(expectedCrc), Long.toHexString(calculatedCrc));
        }
        
        return valid;
    }
    
    /**
     * Read CRC from buffer
     */
    private long readCrc(ByteBuf in) {
        long crc = 0;
        
        if (config.isCrcBigEndian()) {
            // Big-endian
            for (int i = 0; i < config.getCrcBytes(); i++) {
                crc = (crc << 8) | (in.readByte() & 0xFF);
            }
        } else {
            // Little-endian
            for (int i = 0; i < config.getCrcBytes(); i++) {
                crc = crc | ((long)(in.readByte() & 0xFF) << (i * 8));
            }
        }
        
        return crc;
    }
}

