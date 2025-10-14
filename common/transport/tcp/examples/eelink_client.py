#!/usr/bin/env python3
#
# Copyright © 2016-2025 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Eelink（优联时空）设备协议客户端 - Token认证方式
厂商：北京优联时空科技有限公司

本客户端实现基于Token的设备认证和数据上报功能。

使用方法：
  python3 eelink_client.py YOUR_DEVICE_TOKEN
  python3 eelink_client.py YOUR_DEVICE_TOKEN --host 192.168.1.100 --port 8883

参数说明：
  token              设备访问令牌（必需）
  --host HOST        服务器地址（默认: localhost）
  --port PORT        服务器端口（默认: 8883）
  -v, --verbose      显示详细信息
  -h, --help         显示帮助信息

获取 Token：
  1. 登录 Thingsboard
  2. 进入"设备"页面
  3. 创建或选择设备
  4. 复制"访问令牌"

注意：
- 如需使用16进制字符串测试，请使用 eelink_simple_test.py
- 本客户端专注于 Thingsboard Token 认证方式

帧格式：
[起始段2] [方向1] [帧长度1] [帧代号1] [功能码2] [地址段M] [数据段N] [CRC2] [结束段2]

CRC算法：CRC16-MODBUS (多项式0xA001, 初值0xFFFF, 小端序存储)
"""

import socket
import struct
import json
import time

class EelinkProtocolClient:
    """Eelink（优联时空）协议客户端"""
    
    # 协议常量
    HEADER_BASE = 0x88
    HEADER_SECOND = 0xFB
    FOOTER = bytes([0xFC, 0xFC])
    
    DIRECTION_REQUEST = 0xFA    # 发起帧
    DIRECTION_RESPONSE = 0xAF   # 应答帧
    DIRECTION_BROADCAST = 0xAA  # 广播帧
    
    # 地址类型
    ADDR_TYPE_WSN = 0x0   # WSN地址（2字节）
    ADDR_TYPE_GPRS = 0x6  # GPRS地址（4字节）
    ADDR_TYPE_MAC = 0xF   # MAC地址（8字节）
    
    def __init__(self, host='localhost', port=8883):
        self.host = host
        self.port = port
        self.sock = None
    
    def calculate_crc16(self, data):
        """
        CRC16-MODBUS计算 (也称CRC16-IBM)
        多项式: 0xA001 (反向的0x8005)
        初始值: 0xFFFF
        
        北京优联时空科技有限公司（Eelink）协议使用此算法
        """
        crc = 0xFFFF
        
        for byte in data:
            crc ^= byte  # 直接异或，不左移
            for _ in range(8):
                if crc & 0x0001:  # 检查最低位
                    crc = (crc >> 1) ^ 0xA001  # 右移并与0xA001异或
                else:
                    crc = crc >> 1  # 只右移
        
        return crc
    
    def build_frame(self, data, frame_code=0x01, addr_type=ADDR_TYPE_WSN):
        """
        构建Eelink协议帧
        
        Args:
            data: 数据段内容（bytes）
            frame_code: 帧代号
            addr_type: 地址类型（决定地址段长度）
        """
        # 确定地址段长度
        if addr_type == self.ADDR_TYPE_WSN:
            address = bytes([0x00, 0x00])  # 2字节WSN地址
        elif addr_type == self.ADDR_TYPE_GPRS:
            address = bytes([0x00, 0x00, 0x00, 0x00])  # 4字节GPRS地址
        else:  # ADDR_TYPE_MAC
            address = bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])  # 8字节MAC
        
        # 功能码（2字节）
        function_code1 = (addr_type << 4) | 0x00  # 第一位：地址类型
        function_code2 = 0x10  # 第二位：路由次数1，第三位：立即处理0，第四位：当前级数1
        
        # 计算帧长度：帧代号(1) + 功能码(2) + 地址段(M) + 数据段(N) + CRC(2)
        frame_length = 1 + 2 + len(address) + len(data) + 2
        
        # 分离长度的高3位和低8位
        length_high_bits = (frame_length >> 8) & 0x07
        length_low_bits = frame_length & 0xFF
        
        # 构建起始段
        start_byte1 = self.HEADER_BASE | length_high_bits
        start_segment = bytes([start_byte1, self.HEADER_SECOND])
        
        # 方向（发起帧）
        direction = bytes([self.DIRECTION_REQUEST])
        
        # 帧长度（低8位）
        length_byte = bytes([length_low_bits])
        
        # 帧代号
        frame_code_byte = bytes([frame_code])
        
        # 功能码
        function_code = bytes([function_code1, function_code2])
        
        # 计算CRC（从帧长度到数据段）
        crc_data = bytearray()
        crc_data.extend(length_byte)
        crc_data.extend(frame_code_byte)
        crc_data.extend(function_code)
        crc_data.extend(address)
        crc_data.extend(data)
        
        crc = self.calculate_crc16(bytes(crc_data))
        crc_bytes = struct.pack('<H', crc)  # 小端序（LSB first）
        
        # 组装完整帧
        frame = bytearray()
        frame.extend(start_segment)  # 起始段
        frame.extend(direction)      # 方向
        frame.extend(length_byte)    # 帧长度
        frame.extend(frame_code_byte)# 帧代号
        frame.extend(function_code)  # 功能码
        frame.extend(address)        # 地址段
        frame.extend(data)           # 数据段
        frame.extend(crc_bytes)      # CRC
        frame.extend(self.FOOTER)    # 结束段
        
        return bytes(frame)
    
    def parse_frame(self, timeout=5):
        """解析接收到的帧"""
        self.sock.settimeout(timeout)
        
        try:
            # 读取起始段（2字节）
            start_bytes = self.sock.recv(2)
            if len(start_bytes) != 2:
                raise ValueError("Failed to read start segment")
            
            start_byte1 = start_bytes[0]
            start_byte2 = start_bytes[1]
            
            # 验证起始段
            if (start_byte1 & 0xF8) != (self.HEADER_BASE & 0xF8) or start_byte2 != self.HEADER_SECOND:
                raise ValueError(f"Invalid start segment: 0x{start_byte1:02X} 0x{start_byte2:02X}")
            
            # 提取长度高3位
            length_high_bits = start_byte1 & 0x07
            
            # 读取方向
            direction = self.sock.recv(1)[0]
            
            # 读取帧长度（低8位）
            length_low_bits = self.sock.recv(1)[0]
            
            # 计算完整帧长度
            frame_length = (length_high_bits << 8) | length_low_bits
            
            print(f"  Frame length: {frame_length} bytes")
            
            # 读取帧内容（帧长度字节数）
            frame_data = self.sock.recv(frame_length)
            if len(frame_data) != frame_length:
                raise ValueError(f"Expected {frame_length} bytes, got {len(frame_data)}")
            
            # 读取结束段
            footer = self.sock.recv(2)
            if footer != self.FOOTER:
                raise ValueError(f"Invalid footer: {footer.hex()}")
            
            # 解析帧内容
            offset = 0
            frame_code = frame_data[offset]
            offset += 1
            
            function_code1 = frame_data[offset]
            function_code2 = frame_data[offset + 1]
            offset += 2
            
            # 确定地址长度
            addr_type_nibble = (function_code1 >> 4) & 0x0F
            if addr_type_nibble == 0x0:
                addr_len = 2
            elif addr_type_nibble == 0x6:
                addr_len = 4
            elif addr_type_nibble == 0xF:
                addr_len = 8
            else:
                addr_len = 2
            
            address = frame_data[offset:offset + addr_len]
            offset += addr_len
            
            # 数据段 = 总长度 - 帧代号(1) - 功能码(2) - 地址段 - CRC(2)
            data_len = frame_length - 1 - 2 - addr_len - 2
            payload = frame_data[offset:offset + data_len]
            
            # 验证CRC
            crc_data = bytearray()
            crc_data.append(length_low_bits)
            crc_data.extend(frame_data[:offset + data_len])
            
            received_crc = struct.unpack('<H', frame_data[offset + data_len:offset + data_len + 2])[0]  # 小端序
            calculated_crc = self.calculate_crc16(bytes(crc_data))
            
            if received_crc != calculated_crc:
                raise ValueError(f"CRC mismatch: expected 0x{received_crc:04X}, got 0x{calculated_crc:04X}")
            
            return payload.decode('utf-8')
            
        except socket.timeout:
            raise TimeoutError("Timeout waiting for response")
    
    def connect(self):
        """连接服务器"""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))
        print(f"✓ Connected to {self.host}:{self.port}")
    
    def authenticate(self, token):
        """
        Eelink设备登陆
        帧代号：0x41
        数据段：设备Token（ASCII字符串）
        响应：0x00成功，0x01失败
        """
        print(f"\n→ Eelink Login with token: {token}")
        
        # 数据段直接是设备Token
        login_data = token.encode('ascii')
        
        # 使用帧代号0x41（设备登陆）
        frame = self.build_frame(login_data, frame_code=0x41)
        
        # 打印帧信息
        print(f"  Sending login frame ({len(frame)} bytes):")
        print(f"  HEX: {frame.hex(' ').upper()}")
        
        self.sock.sendall(frame)
        
        # 接收登陆响应
        response = self.parse_login_response()
        
        return response
    
    def parse_login_response(self, timeout=5):
        """解析登陆响应帧"""
        self.sock.settimeout(timeout)
        
        try:
            # 读取起始段（2字节）
            start_bytes = self.sock.recv(2)
            if len(start_bytes) != 2:
                raise ValueError("Failed to read start segment")
            
            start_byte1 = start_bytes[0]
            start_byte2 = start_bytes[1]
            
            # 验证起始段
            if (start_byte1 & 0xF8) != (self.HEADER_BASE & 0xF8) or start_byte2 != self.HEADER_SECOND:
                raise ValueError(f"Invalid start segment: 0x{start_byte1:02X} 0x{start_byte2:02X}")
            
            # 提取长度高3位
            length_high_bits = start_byte1 & 0x07
            
            # 读取方向
            direction = self.sock.recv(1)[0]
            print(f"  Direction: 0x{direction:02X} ({'Response' if direction == self.DIRECTION_RESPONSE else 'Unknown'})")
            
            # 读取帧长度（低8位）
            length_low_bits = self.sock.recv(1)[0]
            
            # 计算完整帧长度
            frame_length = (length_high_bits << 8) | length_low_bits
            print(f"  Frame length: {frame_length} bytes")
            
            # 读取帧内容（帧长度字节数）
            frame_data = self.sock.recv(frame_length)
            if len(frame_data) != frame_length:
                raise ValueError(f"Expected {frame_length} bytes, got {len(frame_data)}")
            
            # 读取结束段
            footer = self.sock.recv(2)
            if footer != self.FOOTER:
                raise ValueError(f"Invalid footer: {footer.hex()}")
            
            # 解析帧内容
            offset = 0
            frame_code = frame_data[offset]
            offset += 1
            print(f"  Frame code: 0x{frame_code:02X}")
            
            function_code1 = frame_data[offset]
            function_code2 = frame_data[offset + 1]
            offset += 2
            
            # 确定地址长度
            addr_type_nibble = (function_code1 >> 4) & 0x0F
            if addr_type_nibble == 0x0:
                addr_len = 2
            elif addr_type_nibble == 0x6:
                addr_len = 4
            elif addr_type_nibble == 0xF:
                addr_len = 8
            else:
                addr_len = 2
            
            address = frame_data[offset:offset + addr_len]
            offset += addr_len
            
            # 数据段 = 总长度 - 帧代号(1) - 功能码(2) - 地址段 - CRC(2)
            data_len = frame_length - 1 - 2 - addr_len - 2
            payload = frame_data[offset:offset + data_len]
            
            # 验证CRC
            crc_data = bytearray()
            crc_data.append(length_low_bits)
            crc_data.extend(frame_data[:offset + data_len])
            
            received_crc = struct.unpack('<H', frame_data[offset + data_len:offset + data_len + 2])[0]  # 小端序
            calculated_crc = self.calculate_crc16(bytes(crc_data))
            
            if received_crc != calculated_crc:
                raise ValueError(f"CRC mismatch: expected 0x{received_crc:04X}, got 0x{calculated_crc:04X}")
            
            # 登陆响应数据段只有1个字节：0x00成功，0x01失败
            if data_len >= 1:
                result = payload[0]
                print(f"  Login result: 0x{result:02X} ({'SUCCESS' if result == 0x00 else 'FAILURE'})")
                
                if result == 0x00:
                    print("← Login SUCCESS!")
                    return True
                else:
                    print("← Login FAILED!")
                    return False
            else:
                raise ValueError("Invalid login response: no result byte")
            
        except socket.timeout:
            raise TimeoutError("Timeout waiting for login response")
    
    def send_telemetry(self, data):
        """发送遥测数据"""
        message = f"TELEMETRY:{json.dumps(data)}".encode('utf-8')
        print(f"\n→ Sending telemetry: {data}")
        
        frame = self.build_frame(message)
        print(f"  Frame: {len(frame)} bytes")
        
        self.sock.sendall(frame)
        
        response = self.parse_frame()
        print(f"← Response: {response}")
        
        return response.strip() == "TELEMETRY_OK"
    
    def send_attributes(self, data):
        """发送属性"""
        message = f"ATTRIBUTES:{json.dumps(data)}".encode('utf-8')
        print(f"\n→ Sending attributes: {data}")
        
        frame = self.build_frame(message)
        self.sock.sendall(frame)
        
        response = self.parse_frame()
        print(f"← Response: {response}")
        
        return response.strip() == "ATTRIBUTES_OK"
    
    def disconnect(self):
        """断开连接"""
        if self.sock:
            self.sock.close()
            print("\n✓ Disconnected")


def main():
    """主函数 - 基于Token的Eelink设备认证测试"""
    import argparse
    
    parser = argparse.ArgumentParser(
        description='Eelink协议客户端 - Token认证方式',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  # 使用Token认证
  python3 eelink_client.py YOUR_DEVICE_TOKEN
  
  # 指定服务器和端口
  python3 eelink_client.py YOUR_DEVICE_TOKEN --host 192.168.1.100 --port 8883
  
  # 显示详细信息
  python3 eelink_client.py YOUR_DEVICE_TOKEN --verbose

注意:
  - TOKEN 是必需参数，请从 Thingsboard 设备配置中获取
  - 默认连接到 localhost:8883
        """
    )
    
    parser.add_argument('token', help='设备访问令牌（Access Token）')
    parser.add_argument('--host', default='localhost', help='服务器地址 (默认: localhost)')
    parser.add_argument('--port', type=int, default=8883, help='服务器端口 (默认: 8883)')
    parser.add_argument('-v', '--verbose', action='store_true', help='显示详细信息')
    
    args = parser.parse_args()
    
    HOST = args.host
    PORT = args.port
    TOKEN = args.token
    
    print("=" * 60)
    print("Eelink Protocol Client - Token Authentication")
    print("厂商：北京优联时空科技有限公司")
    print("=" * 60)
    print(f"Server: {HOST}:{PORT}")
    print(f"Token: {TOKEN}")
    print("=" * 60)
    
    client = EelinkProtocolClient(HOST, PORT)
    
    try:
        # 连接
        client.connect()
        
        # Eelink设备登陆（帧代号0x41）
        if not client.authenticate(TOKEN):
            print("\n✗ Eelink Login failed!")
            return
        
        print("\n✓ Eelink Login successful!")
        print("\n提示:")
        print("- 数据上报功能(0x46)、心跳(0x43)等其他功能尚未在服务器端实现")
        print("- 如需使用16进制字符串测试，请使用: python3 eelink_simple_test.py")
        
        # TODO: 以下功能需要在服务器端实现相应的Eelink帧处理后再启用
        # # 发送遥测数据
        # telemetry = {
        #     "temperature": 25.5,
        #     "humidity": 60,
        #     "pressure": 1013.25
        # }
        # client.send_telemetry(telemetry)
        # 
        # # 发送属性
        # attributes = {
        #     "model": "EelinkDevice",
        #     "firmwareVersion": "1.0.0",
        #     "protocol": "eelink_binary",
        #     "vendor": "Beijing Youlinkedin Technology Co., Ltd."
        # }
        # client.send_attributes(attributes)
        
    except KeyboardInterrupt:
        print("\n\n→ Stopping...")
    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.disconnect()

if __name__ == "__main__":
    main()

