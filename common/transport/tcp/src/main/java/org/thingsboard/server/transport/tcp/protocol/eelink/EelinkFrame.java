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

import lombok.Data;

/**
 * Eelink（优联时空）设备帧数据结构
 * 厂商：北京优联时空科技有限公司
 */
@Data
public class EelinkFrame {
    private byte direction;        // 方向：0xFA发起, 0xAF应答, 0xAA广播
    private byte frameCode;        // 帧代号
    private byte functionCode1;    // 功能码第1字节
    private byte functionCode2;    // 功能码第2字节
    private byte[] address;        // 地址段（长度可变：2/4/8字节）
    private byte[] data;           // 数据段（负载）
    
    /**
     * 判断是否为发起帧
     */
    public boolean isRequest() {
        return direction == EelinkProtocolConfig.DIRECTION_REQUEST;
    }
    
    /**
     * 判断是否为应答帧
     */
    public boolean isResponse() {
        return direction == EelinkProtocolConfig.DIRECTION_RESPONSE;
    }
    
    /**
     * 判断是否为广播帧
     */
    public boolean isBroadcast() {
        return direction == EelinkProtocolConfig.DIRECTION_BROADCAST;
    }
    
    /**
     * 获取地址类型
     */
    public String getAddressType() {
        int highNibble = (functionCode1 >> 4) & 0x0F;
        switch (highNibble) {
            case 0x0: return "WSN";
            case 0x6: return "GPRS";
            case 0xF: return "MAC";
            default: return "UNKNOWN";
        }
    }

    /**
     * 获取地址16进制字符串
     */
    public String getAddressString() {
        return EelinkProtocolConfig.bytesToHex(address);
    }

    /**
     * 获取数据16进制字符串
     */
    public String getDataString() {
        return EelinkProtocolConfig.bytesToHex(data);    
    }
}
