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
import threading
import random
from datetime import datetime

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
        self.device_address = None  # 存储设备地址用于后续通信
        self.send_lock = threading.Lock()
    
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

    def get_address_type(self, address):
        '''
        根据地址字节数组，获取地址类型
        Args:
            address: 地址字节数组
        Returns:
            addr_type: 地址类型
        '''
        if len(address) == 2:
            return self.ADDR_TYPE_WSN
        elif len(address) == 4:
            return self.ADDR_TYPE_GPRS
        elif len(address) == 8:
            return self.ADDR_TYPE_MAC
        else:
            return None
    
    def build_frame(self, data, frame_code, address, function_code1, function_code2, request=True):
        """
        构建Eelink协议帧
        
        Args:
            data: 数据段内容（bytes）
            frame_code: 帧代号
            address: 地址段内容（bytes）
            request: 是否为请求帧
        """
        # 确定地址段长度和内容

        addr_type = self.get_address_type(address)
        if addr_type is None:
            # 发出异常: 地址类型不合法
            raise ValueError(f"Invalid address type: {address}")
                
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
        frame.extend(function_code)  # 功能码（请求: 61 01）
        frame.extend(address)        # 地址段
        frame.extend(data)           # 数据段
        frame.extend(crc_bytes)      # CRC
        frame.extend(self.FOOTER)    # 结束段
        
        return bytes(frame)
    
    def build_heartbeat_link_payload(self):
        # 心跳类型(0x01) + 保留(0x00)
        return bytes([0x01, 0x00])
    
    def build_data_report_payload(self, channels=None, storage_type=0, storage_seq=1, data_address=b"\x00\x00\x00\x00"):
        # 参照服务器端解析：
        # [加密标识1][数据包数量1][时间6 LE][通道数1][数据地址4 LE][存储信息4 BE float][通道数据 4*N LE floats]

        # 远程测控终端内容定义
        # 内容	长度	说明
        # 压力	4	浮点类型，低位在前
        # 水位	4	浮点类型，低位在前
        # 阀门电压	4	浮点类型，低位在前
        # 电池电压	4	浮点类型，低位在前
        # 开关状态	4	浮点类型，低位在前
        # 设备信息	4	浮点类型，低位在前
        # 瞬时流量	4	浮点类型，低位在前
        # 累计流量	4	浮点类型，低位在前
        # 阀门开度	4	浮点类型，低位在前
        # 阀门开度2	4	浮点类型，低位在前
        # 阀门开度3	4	浮点类型，低位在前
        # 瞬时流量2	4	浮点类型，低位在前
        # 累计流量2	4	浮点类型，低位在前
        # 瞬时流量3	4	浮点类型，低位在前
        # 累计流量3	4	浮点类型，低位在前

        if channels is None:
            channels = [
                (0, random.uniform(0.7, 1.5)),  # 压力
                (1, random.uniform(1.4, 1.5)),  # 水位
                (2, random.uniform(3.0, 3.6)),  # 阀门电压
                (3, random.uniform(3.5, 4.2)),  # 电池电压
                (4, self.state['pump1']),  # 开关状态
                (5, random.uniform(0.0, 100.0)),  # 设备信息
                (6, random.uniform(80, 100.0)),  # 瞬时流量
                (7, random.uniform(10000, 20000)),  # 累计流量
                (8, self.state['valve1']),  # 阀门开度
                (9, self.state['valve2']),  # 阀门开度2
                (10, self.state['valve3']),  # 阀门开度3
                (11, random.uniform(80, 100.0)),  # 瞬时流量2
                (12, random.uniform(10000,20000)),  # 累计流量2
                (13, random.uniform(80, 100.0)),  # 瞬时流量3
                (14, random.uniform(20000, 30000)),  # 累计流量3
            ]
        now = datetime.now()
        # 加密标识0x00，数据包数量0x01
        buf = bytearray()
        buf.append(0x00)
        buf.append(0x01)
        # 时间6字节（YY MM DD hh mm ss）LE 单字节各自
        buf.extend(bytes([
            now.year - 2000,
            now.month,
            now.day,
            now.hour,
            now.minute,
            now.second
        ]))
        # 通道数量
        buf.append(len(channels))
        # 数据地址4字节（低位在前）
        if len(data_address) != 4:
            data_address = b"\x00\x00\x00\x00"
        buf.extend(data_address)
        # 存储信息：storage_type*1000000 + storage_seq，作为BE float
        storage_info = float(storage_type * 1000000 + storage_seq)
        buf.extend(struct.pack('>f', storage_info))
        # 通道数据：每个为LE float
        for _, value in channels:
            buf.extend(struct.pack('<f', float(value)))
        return bytes(buf)

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

    def parse_raw_frame(self, timeout=None):
        # 通用帧解析，返回字典
        if timeout is not None:
            self.sock.settimeout(timeout)
        # 起始段
        start_bytes = self.sock.recv(2)
        if len(start_bytes) != 2:
            return None
        start_byte1, start_byte2 = start_bytes[0], start_bytes[1]
        if (start_byte1 & 0xF8) != (self.HEADER_BASE & 0xF8) or start_byte2 != self.HEADER_SECOND:
            return None
        length_high_bits = start_byte1 & 0x07
        direction = self.sock.recv(1)[0]
        length_low_bits = self.sock.recv(1)[0]
        frame_length = (length_high_bits << 8) | length_low_bits
        frame_data = self.sock.recv(frame_length)
        if len(frame_data) != frame_length:
            return None
        footer = self.sock.recv(2)
        if footer != self.FOOTER:
            return None
        offset = 0
        frame_code = frame_data[offset]
        offset += 1
        function_code1 = frame_data[offset]
        function_code2 = frame_data[offset + 1]
        offset += 2
        # 地址长度从function_code1高4位判断
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
        data_len = frame_length - 1 - 2 - addr_len - 2
        payload = frame_data[offset:offset + data_len]
        return {
            'direction': direction,
            'frame_code': frame_code,
            'function_code1': function_code1,
            'function_code2': function_code2,
            'address': address,
            'payload': payload
        }
    
    def connect(self):
        """连接服务器"""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))
        print(f"✓ Connected to {self.host}:{self.port}")
        # 设备状态（模拟）
        self.state = {
            'pump1': False,
            'pump2': False,
            'valve1': False,
            'valve2': False,
            'valve3': False,
        }
    
    def authenticate(self, token):
        """
        Eelink设备登陆
        帧代号：0x41
        地址段：设备Token（ASCII字符串转换为字节）
        数据段：空（或保留字段）
        响应：0x00成功，0x01失败
        """
        print(f"\n→ Eelink Login with token: {token}")
                
        # 保存设备地址用于后续通信
        self.device_address = bytes.fromhex(token)
        
        # 数据段: 
        # 19	10字节CCID+8字节IMEI+1字节（复位原因+高低位地址）
        # 4	软件版本（浮点数，2位小数，高位在前）
        # 4	硬件版本（浮点数，2位小数，高位在前）
        # 1	复位次数
        login_data = bytearray()
        # 生成10字节CCID
        import random
        ccid = ''.join(random.choices('0123456789', k=10))
        ccid_bytes = ccid.encode('ascii')
        login_data.extend(ccid_bytes)
        # 生成8字节IMEI
        imei = ''.join(random.choices('0123456789', k=8))
        imei_bytes = imei.encode('ascii')
        login_data.extend(imei_bytes)
        # 生成1字节（复位原因+高低位地址）
        reset_reason = random.randint(0, 15)
        reset_address = random.randint(0, 15)
        reset_info = (reset_reason << 4) | reset_address
        login_data.extend(struct.pack('>B', reset_info))
        # 生成4字节软件版本
        software_version = random.uniform(0.00, 100.00)
        login_data.extend(struct.pack('>f', software_version))
        # 生成4字节硬件版本
        hardware_version = random.uniform(0.00, 100.00)
        login_data.extend(struct.pack('>f', hardware_version))
        # 生成1字节复位次数
        reset_count = random.randint(0, 255)
        login_data.extend(struct.pack('>B', reset_count))

        # 使用帧代号0x41（设备登陆），地址段为token（请求功能码 0x61 0x01）
        frame = self.build_frame(login_data, frame_code=0x41, address=self.device_address, function_code1=0x61, function_code2=0x01, request=True)
        
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
    
    def send_heartbeat(self):
        payload = self.build_heartbeat_link_payload()
        frame = self.build_frame(payload, frame_code=0x43, address=self.device_address, function_code1=0x61, function_code2=0x01, request=True)
        with self.send_lock:
            self.sock.sendall(frame)
        # 接收统一由接收线程处理，避免并发读取冲突

    def send_data_report(self):
        payload = self.build_data_report_payload()
        frame = self.build_frame(payload, frame_code=0x46, address=self.device_address, function_code1=0x61, function_code2=0x01, request=True)
        with self.send_lock:
            self.sock.sendall(frame)
        # 接收统一由接收线程处理

    def _handle_command_request(self, frame):
        fc = frame['frame_code']
        data = frame['payload']
        # 0x81 开泵关阀, 0x01 关泵开阀
        if fc in (0x81, 0x01):
            if len(data) < 1:
                return
            device_code = data[0]
            # 更新状态
            if fc == 0x81:
                # open: 1=泵2,2=泵1,3=阀1,4=阀2,5=阀3
                if device_code == 1:
                    self.state['pump2'] = True
                elif device_code == 2:
                    self.state['pump1'] = True
                elif device_code == 3:
                    self.state['valve1'] = True
                elif device_code == 4:
                    self.state['valve2'] = True
                elif device_code == 5:
                    self.state['valve3'] = True
                print(f"→ Command(open) device={device_code} -> state={self.state}")
            else:
                # close: 0=泵1泵2,1=泵1,2=泵2,3=阀1,4=阀2,5=阀3
                if device_code == 0:
                    self.state['pump1'] = False
                    self.state['pump2'] = False
                elif device_code == 1:
                    self.state['pump1'] = False
                elif device_code == 2:
                    self.state['pump2'] = False
                elif device_code == 3:
                    self.state['valve1'] = False
                elif device_code == 4:
                    self.state['valve2'] = False
                elif device_code == 5:
                    self.state['valve3'] = False
                print(f"→ Command(close) device={device_code} -> state={self.state}")

            # 回复成功：function_code2=0x00，数据段为设备编号1字节
            resp_data = bytes([device_code])
            # 使用设备地址构建响应帧
            resp_frame = self.build_frame(resp_data, frame_code=fc, address=self.device_address, function_code1=0x61, function_code2=0x00, request=False)
            # 调整function_code2为0x00：重写第7字节（功能码第二字节）
            # 起始2 + 方向1 + 长度1 + 帧代号1 + 功能码2 -> 索引(2+1+1+1+1)=6 是功能码2
            resp_frame = bytearray(resp_frame)
            resp_frame[6] = 0x00
            with self.send_lock:
                self.sock.sendall(bytes(resp_frame))
            print("← Command response sent (success)")

    def receive_loop(self):
        # 设置接收超时为较短时间，避免长时间阻塞
        self.sock.settimeout(2.0)
        while True:
            try:
                frame = self.parse_raw_frame(timeout=2.0)
                if not frame:
                    continue
                # 打印收到的帧信息
                fc = frame['frame_code']
                print(f"← Received frame: code=0x{fc:02X}, dir=0x{frame['direction']:02X}, fc2=0x{frame['function_code2']:02X}")
                
                # 处理服务器下发的控制请求（0x81开泵, 0x01关泵）
                if fc in (0x81, 0x01):
                    self._handle_command_request(frame)
                # 处理响应帧（心跳、数据上报等的响应）
                elif fc in (0x43, 0x46, 0x41):
                    # 这些是对我们发送的请求的响应
                    if frame['function_code2'] == 0x00:
                        print(f"  ✓ Success response")
                    else:
                        print(f"  ⚠ Response with fc2=0x{frame['function_code2']:02X}")
            except socket.timeout:
                # 超时是正常的，继续等待
                pass
            except Exception as e:
                print(f"recv error: {e}")
                time.sleep(1)

    def start_periodic_tasks(self, hb_interval=30, data_interval=60):
        def hb_loop():
            while True:
                time.sleep(hb_interval)
                print(f"\n→ Sending heartbeat...")
                self.send_heartbeat()
        def data_loop():
            while True:
                time.sleep(data_interval)
                print(f"\n→ Sending data report...")
                self.send_data_report()
        # threading.Thread(target=hb_loop, daemon=True).start()
        threading.Thread(target=data_loop, daemon=True).start()
        threading.Thread(target=self.receive_loop, daemon=True).start()
    
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
        print("\n→ Starting periodic heartbeat(0x43) and data report(0x46)...")
        client.start_periodic_tasks(hb_interval=20, data_interval=40)
        # 持续运行直到中断
        while True:
            time.sleep(1)
                
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
