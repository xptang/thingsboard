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

/**
 * Eelink（优联时空）协议配置
 * 厂商：北京优联时空科技有限公司
 * 
 * 帧格式：
 * [起始段2] [方向1] [帧长度1] [帧代号1] [功能码2] [地址段M] [数据段N] [CRC2] [结束段2]
 */
public class EelinkProtocolConfig {

    // 协议常量
    public static final byte HEADER_BASE = (byte) 0x88;  // 起始段第一字节基础值（去除低3位）
    public static final byte HEADER_SECOND = (byte) 0xFB; // 起始段第二字节
    
    public static final byte[] FOOTER = {(byte) 0xFC, (byte) 0xFC}; // 结束段
    
    // 方向代码
    public static final byte DIRECTION_REQUEST = (byte) 0xFA;   // 发起帧
    public static final byte DIRECTION_RESPONSE = (byte) 0xAF;  // 应答帧
    public static final byte DIRECTION_BROADCAST = (byte) 0xAA; // 广播帧
    
    // 功能码第一位（决定地址段长度）
    public static final byte ADDR_TYPE_WSN = (byte) 0x0;    // WSN 2字节
    public static final byte ADDR_TYPE_GPRS = (byte) 0x6;   // GPRS 4字节
    public static final byte ADDR_TYPE_MAC = (byte) 0xF;    // MAC 8字节
    
    /**
     * 根据功能码第一位获取地址段长度
     */
    public static int getAddressLength(byte functionCodeByte1) {
        int highNibble = (functionCodeByte1 >> 4) & 0x0F;
        
        switch (highNibble) {
            case 0x0:
                return 2;  // WSN地址
            case 0x6:
                return 4;  // GPRS地址
            case 0xF:
                return 8;  // MAC地址
            default:
                return 2;  // 默认WSN
        }
    }
    
    /**
     * 从起始段提取帧长度高3位
     */
    public static int extractLengthHighBits(byte startByte1) {
        return startByte1 & 0x07;
    }
    
    /**
     * 构建起始段第一字节（包含长度高3位）
     */
    public static byte buildStartByte1(int lengthHighBits) {
        return (byte) (HEADER_BASE | (lengthHighBits & 0x07));
    }
    
    /**
     * 验证起始段第一字节
     */
    public static boolean isValidStartByte1(byte b) {
        return (b & 0xF8) == (HEADER_BASE & 0xF8);
    }

    /**
     * 字节数组转16进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
    
    /**
     * 16进制字符串转字节数组
     * 
     * @param hexString 16进制字符串（如"93640000"）
     * @return 字节数组（如[0x93, 0x64, 0x00, 0x00]）
     */
    public static byte[] hexToBytes(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return new byte[0];
        }
        
        // 移除可能的空格和0x前缀
        hexString = hexString.replaceAll("\\s+", "").replaceAll("0x", "");
        
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        
        return data;
    }
}

