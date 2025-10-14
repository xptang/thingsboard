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

import java.util.zip.CRC32;

/**
 * CRC Calculation Utilities
 */
public class CrcUtil {

    /**
     * Calculate CRC16-MODBUS (also known as CRC16-IBM)
     * Polynomial: 0xA001 (reversed 0x8005)
     * Initial value: 0xFFFF
     * 
     * 北京优联时空科技有限公司（Eelink）协议使用此算法
     */
    public static int calculateCrc16(byte[] data) {
        return calculateCrc16(data, 0, data.length);
    }
    
    public static int calculateCrc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);  // 直接异或，不左移
            
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {  // 检查最低位
                    crc = (crc >> 1) ^ 0xA001;  // 右移并与0xA001异或
                } else {
                    crc = crc >> 1;  // 只右移
                }
            }
        }
        
        return crc & 0xFFFF;
    }
    
    /**
     * Calculate CRC32
     */
    public static long calculateCrc32(byte[] data) {
        return calculateCrc32(data, 0, data.length);
    }
    
    public static long calculateCrc32(byte[] data, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, offset, length);
        return crc32.getValue();
    }
    
    /**
     * Verify CRC16
     */
    public static boolean verifyCrc16(byte[] data, int offset, int length, int expectedCrc) {
        int calculatedCrc = calculateCrc16(data, offset, length);
        return calculatedCrc == expectedCrc;
    }
    
    /**
     * Verify CRC32
     */
    public static boolean verifyCrc32(byte[] data, int offset, int length, long expectedCrc) {
        long calculatedCrc = calculateCrc32(data, offset, length);
        return calculatedCrc == expectedCrc;
    }
}

