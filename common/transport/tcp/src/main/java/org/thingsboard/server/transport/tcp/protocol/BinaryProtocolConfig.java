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

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.transport.tcp.TbTcpTransportComponent;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * Binary Protocol Configuration
 */
@Component
@TbTcpTransportComponent
@Data
@Slf4j
public class BinaryProtocolConfig {

    // 包头配置
    @Value("${transport.tcp.binary.header:0xAA55}")
    private String headerHex;
    
    // 包尾配置
    @Value("${transport.tcp.binary.footer:0x55AA}")
    private String footerHex;
    
    // 长度字段字节数：1, 2, 4
    @Value("${transport.tcp.binary.length_bytes:2}")
    private int lengthBytes;
    
    // 长度字节序：big_endian 或 little_endian
    @Value("${transport.tcp.binary.length_endian:big_endian}")
    private String lengthEndian;
    
    // CRC类型：crc16, crc32, none
    @Value("${transport.tcp.binary.crc_type:crc16}")
    private String crcType;
    
    // CRC字节序
    @Value("${transport.tcp.binary.crc_endian:big_endian}")
    private String crcEndian;
    
    private byte[] header;
    private byte[] footer;
    private int crcBytes;
    
    @PostConstruct
    public void init() {
        // 解析包头
        header = hexStringToByteArray(headerHex);
        
        // 解析包尾
        footer = hexStringToByteArray(footerHex);
        
        // 确定CRC字节数
        switch (crcType.toLowerCase()) {
            case "crc16":
                crcBytes = 2;
                break;
            case "crc32":
                crcBytes = 4;
                break;
            case "none":
                crcBytes = 0;
                break;
            default:
                crcBytes = 2;
                log.warn("Unknown CRC type: {}, using CRC16", crcType);
        }
        
        log.info("Binary Protocol Config initialized:");
        log.info("  Header: {} ({} bytes)", headerHex, header.length);
        log.info("  Footer: {} ({} bytes)", footerHex, footer.length);
        log.info("  Length: {} bytes, {}", lengthBytes, lengthEndian);
        log.info("  CRC: {} ({} bytes), {}", crcType, crcBytes, crcEndian);
    }
    
    /**
     * Convert hex string to byte array
     * Supports formats: 0xAA55, AA55, AA 55, 0xAA 0x55
     */
    private byte[] hexStringToByteArray(String hex) {
        // Remove 0x prefix and spaces
        String cleanHex = hex.replaceAll("0x", "").replaceAll("\\s+", "");
        
        int len = cleanHex.length();
        byte[] data = new byte[len / 2];
        
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(cleanHex.charAt(i), 16) << 4)
                    + Character.digit(cleanHex.charAt(i + 1), 16));
        }
        
        return data;
    }
    
    public boolean isBigEndian() {
        return "big_endian".equalsIgnoreCase(lengthEndian);
    }
    
    public boolean isCrcBigEndian() {
        return "big_endian".equalsIgnoreCase(crcEndian);
    }
    
    public int getFrameOverhead() {
        return header.length + lengthBytes + crcBytes + footer.length;
    }
}

