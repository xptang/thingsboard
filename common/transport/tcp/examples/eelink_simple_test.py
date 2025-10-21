#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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
Eelink协议简化测试客户端 - 直接发送文档实例数据

使用协议文档末尾的实例部分的真实数据进行测试
"""

import socket
import time
import sys

# 测试样例数据（来自协议文档实例部分）
TEST_SAMPLES = [
    {
        "name": "设备登陆 (0x41)",
        "request": "88FBFA254161019364000089861122242044232999086052105288759740464ED400459740000BD33FFCFC",
        "expected_response": "88FBAF0A41610093640000005A47FCFC",
        "description": "设备注册请求，包含CCID、IMEI、软硬件版本信息"
    },
    {
        "name": "链路心跳 (0x43)",
        "request": "88FBFA0B4361019364000001009ECEFCFC",
        "expected_response": "88FBAF0B4361009364000001008E0EFCFC",
        "description": "简单的链路保活心跳"
    },
    {
        "name": "状态心跳 (0x43)",
        "request": "88FBFA2B4361019364000002000117021709300C0000012C0000012C0000012C46000000000000000044932000BF65FCFC",
        "expected_response": "88FBAF0D4361009364000002000000EC28FCFC",
        "description": "包含设备状态、时间、周期、信号强度等信息"
    },
    {
        "name": "警情上报 (0x42)",
        "request": "88FBFA2442610193640000170217092F3202936400000604461C400000000000000000000000A53AFCFC",
        "expected_response": "88FBAF0A42610093640000001A52FCFC",
        "description": "设备报警信息上报"
    },
    {
        "name": "数据上报 (0x46)",
        "request": "88FBFA4646610193640000000118030F0F29340B93640000000000000000A03F0000604000004841666666400000803F00000000CDCC744100509A44000048420000F041000034423BA5FCFC",
        "expected_response": "88FBAF0A46610093640000001BA1FCFC",
        "description": "远程测控终端数据上报（11通道：压力1.25/水位3.5/阀门电压12.5/电池电压3.6/开关状态1.0/设备信息0/瞬时流量15.3/累计流量1234.5/阀门开度50.0/阀门开度2:30.0/阀门开度3:45.0）"
    }
]

# 平台控制命令样例（仅供参考，这些是平台→设备的命令）
# 注意：这些命令应该由服务器端（平台）主动发送给设备，而不是由设备发起
CONTROL_COMMANDS_REFERENCE = [
    {
        "name": "开泵关阀 (0x81)",
        "platform_request": "88FBFA0A8161019364000002D607FCFC",
        "device_response": "88FBAF0A8161009364000002D7D6FCFC",
        "description": "平台→设备：控制开泵关阀"
    },
    {
        "name": "关泵开阀 (0x01)",
        "platform_request": "88FBFA0A0161019364000002DE67FCFC",
        "device_response": "88FBAF0A0161009364000002DFB6FCFC",
        "description": "平台→设备：控制关泵开阀"
    },
    {
        "name": "阀门开度控制 (0xF4)",
        "platform_request": "88FBFA0EF46101991E0000424800000025EAFCFC",
        "device_response": "88FBAF0AF46100991E000001D1D5FCFC",
        "description": "平台→设备：控制阀门开度50%"
    }
]


class EelinkSimpleTestClient:
    def __init__(self, host='localhost', port=8883):
        self.host = host
        self.port = port
        self.sock = None
    
    def connect(self):
        """连接服务器"""
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(10)
            self.sock.connect((self.host, self.port))
            print(f"✓ 已连接到 {self.host}:{self.port}")
            return True
        except Exception as e:
            print(f"✗ 连接失败: {e}")
            return False
    
    def send_hex(self, hex_string):
        """发送16进制字符串"""
        data = bytes.fromhex(hex_string)
        self.sock.sendall(data)
        return data
    
    def receive_hex(self, timeout=5):
        """接收并返回16进制字符串"""
        old_timeout = self.sock.gettimeout()
        self.sock.settimeout(timeout)
        try:
            data = self.sock.recv(4096)
            return data.hex().upper()
        except socket.timeout:
            return None
        finally:
            self.sock.settimeout(old_timeout)
    
    def test_sample(self, sample, show_details=True):
        """测试单个样例"""
        print(f"\n{'='*70}")
        print(f"测试: {sample['name']}")
        print(f"说明: {sample['description']}")
        print(f"{'='*70}")
        
        # 发送请求
        print(f"\n→ 发送请求:")
        if show_details:
            print(f"  {self.format_hex(sample['request'])}")
        else:
            print(f"  {sample['request'][:32]}... ({len(sample['request'])//2} 字节)")
        
        try:
            sent_data = self.send_hex(sample['request'])
            print(f"  ✓ 已发送 {len(sent_data)} 字节")
        except Exception as e:
            print(f"  ✗ 发送失败: {e}")
            return False
        
        # 接收响应
        print(f"\n← 接收响应:")
        response = self.receive_hex()
        
        if response is None:
            print(f"  ✗ 超时，未收到响应")
            return False
        
        if show_details:
            print(f"  {self.format_hex(response)}")
        else:
            print(f"  {response}")
        print(f"  ✓ 收到 {len(response)//2} 字节")
        
        # 验证响应
        if 'expected_response' in sample:
            expected = sample['expected_response'].upper()
            if response == expected:
                print(f"\n✅ 响应匹配！")
                return True
            else:
                print(f"\n⚠️  响应不匹配")
                print(f"  期望: {expected}")
                print(f"  实际: {response}")
                return False
        else:
            print(f"\n✓ 测试完成（无期望响应对比）")
            return True
    
    def format_hex(self, hex_string, bytes_per_line=16):
        """格式化16进制字符串为易读格式"""
        hex_clean = hex_string.replace(' ', '').upper()
        result = []
        for i in range(0, len(hex_clean), bytes_per_line * 2):
            chunk = hex_clean[i:i + bytes_per_line * 2]
            formatted = ' '.join([chunk[j:j+2] for j in range(0, len(chunk), 2)])
            result.append(f"  {formatted}")
        return '\n'.join(result)
    
    def disconnect(self):
        """断开连接"""
        if self.sock:
            self.sock.close()
            print("\n✓ 已断开连接")


def run_basic_tests(host='localhost', port=8883, show_details=True):
    """运行基础测试"""
    client = EelinkSimpleTestClient(host, port)
    
    print("="*70)
    print("Eelink协议简化测试客户端")
    print("="*70)
    print(f"服务器: {host}:{port}")
    print(f"测试样例: {len(TEST_SAMPLES)} 个")
    print("="*70)
    
    if not client.connect():
        return
    
    results = []
    try:
        for i, sample in enumerate(TEST_SAMPLES, 1):
            print(f"\n\n{'#'*70}")
            print(f"# 测试 {i}/{len(TEST_SAMPLES)}")
            print(f"{'#'*70}")
            
            result = client.test_sample(sample, show_details)
            results.append((sample['name'], result))
            
            # 短暂延迟
            if i < len(TEST_SAMPLES):
                time.sleep(1)
        
    except KeyboardInterrupt:
        print("\n\n⚠️  测试被用户中断")
    except Exception as e:
        print(f"\n\n✗ 测试出错: {e}")
    finally:
        client.disconnect()
    
    # 打印测试总结
    print("\n\n" + "="*70)
    print("测试总结")
    print("="*70)
    passed = sum(1 for _, result in results if result)
    total = len(results)
    
    for name, result in results:
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"{status}  {name}")
    
    print("="*70)
    print(f"通过: {passed}/{total}  失败: {total-passed}/{total}")
    print("="*70)


def run_listen_mode(host='localhost', port=8883, duration=60):
    """设备监听模式 - 保持连接并等待平台控制命令"""
    client = EelinkSimpleTestClient(host, port)
    
    print("="*70)
    print("Eelink协议 - 设备监听模式")
    print("="*70)
    print("设备将保持连接并等待平台发送的控制命令")
    print(f"监听时长: {duration} 秒")
    print("="*70)
    
    if not client.connect():
        return
    
    try:
        # 先发送登陆请求
        print("\n→ 发送设备登陆请求...")
        login_sample = TEST_SAMPLES[0]  # 第一个是登陆请求
        client.send_hex(login_sample['request'])
        response = client.receive_hex(timeout=5)
        
        if response:
            print(f"← 收到登陆响应: {response[:32]}...")
            print("✓ 设备已登陆，开始监听控制命令...\n")
        else:
            print("✗ 登陆失败，退出监听")
            return
        
        # 持续监听
        print(f"{'='*70}")
        print("等待平台控制命令中... (按 Ctrl+C 退出)")
        print(f"{'='*70}\n")
        
        start_time = time.time()
        last_heartbeat_time = time.time()
        last_data_report_time = time.time()
        command_count = 0
        
        while time.time() - start_time < duration:
            # 定期发送心跳（每30秒）
            if time.time() - last_heartbeat_time >= 40:
                print("\n→ 发送链路心跳...")
                heartbeat = "88FBFA0B4361019364000001009ECEFCFC"
                client.send_hex(heartbeat)
                last_heartbeat_time = time.time()
                time.sleep(1)

            # 定期发送数据上报
            if time.time() - last_data_report_time >= 30:
                print("\n→ 发送数据上报...")
                data_report = "88FBFA4646610193640000000118030F0F29340B93640000000000000000A03F0000604000004841666666400000803F00000000CDCC744100509A44000048420000F041000034423BA5FCFC"
                client.send_hex(data_report)
                last_data_report_time = time.time()
                time.sleep(1)
            
            # 监听平台命令
            try:
                data = client.receive_hex(timeout=2)
                if data:
                    command_count += 1
                    print(f"\n{'='*70}")
                    print(f"← 收到平台命令 #{command_count}")
                    print(f"{'='*70}")
                    print(f"数据: {data}")
                    
                    # 解析命令类型
                    if len(data) >= 10:
                        frame_code = data[8:10]
                        print(f"帧代号: 0x{frame_code}")
                        
                        # 根据文档中的样例识别命令类型
                        if frame_code == '81':
                            print("类型: 开泵关阀")
                        elif frame_code == '01':
                            print("类型: 关泵开阀")
                        elif frame_code == 'F4':
                            print("类型: 阀门开度控制")
                        else:
                            print(f"类型: 未知命令 (0x{frame_code})")
                    
                    print("\n⚠️  注意: 本客户端仅用于测试，未实现命令响应")
                    print("    实际设备应根据命令类型返回相应的响应帧")
                    print(f"{'='*70}\n")
            except:
                pass
            
            time.sleep(0.5)
        
        print(f"\n\n{'='*70}")
        print("监听结束")
        print(f"{'='*70}")
        print(f"收到平台命令数: {command_count}")
        print(f"{'='*70}")
        
    except KeyboardInterrupt:
        print("\n\n⚠️  监听被用户中断")
    except Exception as e:
        print(f"\n\n✗ 监听出错: {e}")
    finally:
        client.disconnect()


def show_control_commands():
    """显示平台控制命令参考"""
    print("="*70)
    print("Eelink协议 - 平台控制命令参考")
    print("="*70)
    print("注意: 这些命令由平台（服务器）发送给设备\n")
    
    for cmd in CONTROL_COMMANDS_REFERENCE:
        print(f"{'='*70}")
        print(f"命令: {cmd['name']}")
        print(f"说明: {cmd['description']}")
        print(f"{'='*70}")
        print(f"平台请求:")
        print(f"  {cmd['platform_request']}")
        print(f"\n设备响应:")
        print(f"  {cmd['device_response']}")
        print()
    
    print("="*70)
    print("如何测试平台控制命令:")
    print("="*70)
    print("1. 使用监听模式: python3 eelink_simple_test.py --listen")
    print("2. 从Thingsboard平台发送RPC命令到设备")
    print("3. 设备将收到并显示平台发送的命令")
    print("="*70)


if __name__ == '__main__':
    import argparse
    
    parser = argparse.ArgumentParser(
        description='Eelink协议简化测试客户端 - 使用文档实例数据',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  # 基础测试（设备上报功能）
  python3 eelink_simple_test.py
  
  # 简化输出
  python3 eelink_simple_test.py --simple
  
  # 监听平台控制命令
  python3 eelink_simple_test.py --listen
  
  # 查看平台控制命令参考
  python3 eelink_simple_test.py --show-commands
  
  # 指定服务器
  python3 eelink_simple_test.py --host 192.168.1.100 --port 8883

注意:
  - 基础测试模拟设备主动上报（登陆、心跳、报警、数据）
  - 控制命令由平台发起，设备接收并响应
  - 使用 --listen 模式可以接收平台发送的控制命令
        """
    )
    parser.add_argument('--host', default='localhost', help='服务器地址 (默认: localhost)')
    parser.add_argument('--port', type=int, default=8883, help='服务器端口 (默认: 8883)')
    parser.add_argument('--simple', action='store_true', help='简化输出（不显示详细hex）')
    parser.add_argument('--listen', action='store_true', help='设备监听模式（等待平台控制命令）')
    parser.add_argument('--duration', type=int, default=60, help='监听模式持续时间（秒，默认60）')
    parser.add_argument('--show-commands', action='store_true', help='显示平台控制命令参考')
    
    args = parser.parse_args()
    
    if args.show_commands:
        show_control_commands()
    elif args.listen:
        run_listen_mode(args.host, args.port, args.duration)
    else:
        run_basic_tests(args.host, args.port, show_details=not args.simple)

