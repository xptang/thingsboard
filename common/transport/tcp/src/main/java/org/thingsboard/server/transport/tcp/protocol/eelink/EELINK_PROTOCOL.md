# Eelink（优联时空）设备协议实现文档

**厂商**：北京优联时空科技有限公司  
**英文简称**：Eelink  
**协议版本**：基于厂商提供的协议文档

## 📋 协议格式

### 完整帧结构

```
+--------+------+--------+--------+--------+--------+--------+-----+--------+
| 起始段 | 方向 | 帧长度 | 帧代号 | 功能码 | 地址段 | 数据段 | CRC | 结束段 |
+--------+------+--------+--------+--------+--------+--------+-----+--------+
| 2字节  | 1字节| 1字节  | 1字节  | 2字节  | M字节  | N字节  | 2字节| 2字节  |
+--------+------+--------+--------+--------+--------+--------+-----+--------+
```

### 字段详解

#### 1. 起始段（2字节）
- **第1字节**：`0x88` | 帧长度高3位
  - 去除低3位后固定为 `0x88`
  - 低3位存储帧长度的第9-11位（支持长度>255）
  - 例如：长度=300(0x12C)，则第1字节=0x88|0x01=0x89
- **第2字节**：固定 `0xFB`

#### 2. 方向（1字节）
- `0xFA` - 发起帧（需要应答）
- `0xAF` - 应答帧
- `0xAA` - 广播帧（无需应答）

#### 3. 帧长度（1字节）
- 计算范围：帧代号 + 功能码 + 地址段 + 数据段 + CRC
- 存储低8位
- 高3位存储在起始段第1字节的低3位
- **不包括**：起始段、方向、帧长度本身、结束段

#### 4. 帧代号（1字节）
- 功能代号，表示协议帧的功能
- 示例：0x01（通用数据）

#### 5. 功能码（2字节）

**第1字节（高4位）**：地址类型
- `0x0` - 透传，WSN设备地址（2字节）
- `0x6` - GPRS设备地址（4字节）
- `0xF` - GPRS设备MAC地址（8字节）

**第1字节（低4位）**：保留

**第2字节**：
- 第1位（高4位）：路由次数（1-10）
- 第2位：缓存标志
  - 0：立即处理
  - 8：缓存
- 第3-4位：当前路由级数

#### 6. 地址段（M字节，可变）
- WSN地址：2字节
- GPRS地址：4字节
- MAC地址：8字节

#### 7. 数据段（N字节，可变）
- 实际传输的数据
- 在本实现中为JSON字符串

#### 8. CRC校验（2字节）
- CRC16-CCITT算法
- 多项式：0x1021
- 初始值：0xFFFF
- **计算范围**：从帧长度字节到数据段
- 字节序：大端序

#### 9. 结束段（2字节）
- 固定：`0xFC 0xFC`

## 📝 示例帧

### 示例1：认证帧（AUTH:TOKEN）

```
完整HEX帧：
89 FB FA 13 01 00 10 00 00 41 55 54 48 3A 54 4F 4B 45 4E XX XX FC FC

字段解析：
89        # 起始段第1字节 = 0x88|0x01 (长度高3位=1)
FB        # 起始段第2字节
FA        # 方向：发起帧
13        # 帧长度低8位 = 19
01        # 帧代号 = 1
00        # 功能码第1字节 = WSN地址类型(0x0)
10        # 功能码第2字节 = 路由1次，立即处理
00 00     # 地址段 = WSN地址
41 55 54 48 3A 54 4F 4B 45 4E  # 数据段 = "AUTH:TOKEN"
XX XX     # CRC16
FC FC     # 结束段
```

### 示例2：遥测帧

```
数据："TELEMETRY:{\"temperature\":25.5}"

完整帧长度计算：
- 帧代号：1字节
- 功能码：2字节
- 地址段：2字节（WSN）
- 数据段：30字节
- CRC：2字节
- 帧长度 = 1+2+2+30+2 = 37 (0x25)

起始段：88 FB（长度<256，高3位=0）
方向：FA（发起帧）
帧长度：25
...
```

## 🔧 配置

在 `thingsboard.yml` 中启用厂商协议：

```yaml
transport:
  tcp:
    enabled: true
    protocol_type: binary    # 使用二进制协议（厂商协议）
    bind_port: 8883
```

**注意**：Eelink协议的帧格式已按照厂商文档实现，不需要额外配置。

## 🧪 测试

### 使用Python客户端

```bash
cd /home/tang/work/thingsboard/common/transport/tcp/examples
python3 eelink_client.py
```

### 手动构造测试帧

```python
import struct

# 构造认证帧
data = b"AUTH:TOKEN"
frame_length = 1 + 2 + 2 + len(data) + 2  # =19=0x13

frame = bytearray()
frame.extend([0x88, 0xFB])       # 起始段
frame.append(0xFA)               # 方向：发起帧
frame.append(0x13)               # 帧长度
frame.append(0x01)               # 帧代号
frame.extend([0x00, 0x10])       # 功能码
frame.extend([0x00, 0x00])       # 地址段（WSN）
frame.extend(data)               # 数据段

# 计算CRC
crc_data = bytes([0x13, 0x01, 0x00, 0x10, 0x00, 0x00]) + data
crc = calculate_crc16(crc_data)
frame.extend(struct.pack('>H', crc))  # CRC

frame.extend([0xFC, 0xFC])       # 结束段

# 发送
sock.sendall(frame)
```

## 📊 协议特点

### 优势
1. ✅ **可靠性高** - CRC16校验
2. ✅ **边界清晰** - 起始段和结束段双重标识
3. ✅ **支持长帧** - 11位长度字段，最大2047字节
4. ✅ **灵活地址** - 支持多种地址类型（2/4/8字节）
5. ✅ **方向标识** - 区分发起/应答/广播帧
6. ✅ **工业级** - 成熟的工业设备协议

### 实现细节
1. **帧边界检测** - 扫描起始段（0x88/0x89/0x8A... + 0xFB）
2. **长度扩展** - 11位长度支持（3位高+8位低）
3. **CRC验证** - 从帧长度到数据段
4. **自动丢弃** - 损坏帧自动丢弃并重新同步

## 🔍 调试

### 查看原始帧（HEX）

```bash
# 使用tcpdump
sudo tcpdump -i lo -X port 8883 -w tcp_dump.pcap

# 使用wireshark
wireshark -i lo -f "port 8883"
```

### Python调试脚本

```python
# 打印帧结构
frame = build_frame(b"TEST")
print("Frame breakdown:")
print(f"  Start:     {frame[0:2].hex()}")
print(f"  Direction: {frame[2:3].hex()}")
print(f"  Length:    {frame[3:4].hex()}")
print(f"  Frame Code:{frame[4:5].hex()}")
print(f"  Func Code: {frame[5:7].hex()}")
print(f"  Address:   {frame[7:9].hex()}")
print(f"  Data:      {frame[9:-4].hex()}")
print(f"  CRC:       {frame[-4:-2].hex()}")
print(f"  Footer:    {frame[-2:].hex()}")
```

## ⚠️ 注意事项

1. **帧长度限制**：最大2047字节（11位）
2. **CRC计算范围**：从帧长度字节到数据段（不包括起始段、方向、结束段）
3. **地址类型匹配**：功能码第1字节决定地址段长度
4. **字节序**：CRC和长度字段都使用大端序
5. **起始段特殊性**：第1字节同时包含协议标识和长度高位

## 📈 性能

| 特性 | 值 |
|-----|-----|
| 最小帧长度 | 13字节 |
| 最大数据长度 | ~2000字节 |
| CRC开销 | 低（硬件实现快） |
| 帧检测效率 | 高（特殊起始段） |
| 解析速度 | 极快 |

## 🎯 与标准协议对比

| 特性 | 字符串协议 | 标准二进制 | 厂商协议 |
|-----|-----------|-----------|---------|
| 帧开销 | 1字节 | 8字节 | 11+M字节 |
| 可靠性 | 低 | 高 | 极高 |
| 复杂度 | 低 | 中 | 高 |
| 长度支持 | 无限 | 64KB | 2KB |
| 地址支持 | 无 | 无 | 有（2/4/8字节） |
| 方向标识 | 无 | 无 | 有 |
| 适用场景 | 开发测试 | 通用 | 优联时空设备 |

## ✅ 实现状态

- [x] EelinkProtocolConfig - 协议常量定义
- [x] EelinkFrame - 帧数据结构
- [x] EelinkFrameDecoder - 帧解码器
- [x] EelinkFrameEncoder - 帧编码器
- [x] CrcUtil - CRC计算工具
- [x] TcpTransportHandler - 集成解码器/编码器
- [x] Python客户端示例
- [x] 完整文档

## 🚀 快速开始

1. **配置**：
```yaml
transport:
  tcp:
    protocol_type: binary
```

2. **重启应用**：
```bash
cd application && mvn spring-boot:run
```

3. **测试**：
```bash
python3 examples/vendor_protocol_client.py
```

4. **查看日志**：
```
[INFO] Using DeviceFrameDecoder (Vendor Protocol)
[INFO] TCP transport started on 0.0.0.0:8883!
[INFO] Decoded frame: direction=0xfa, frameCode=0x1, funcCode=0x0 0x10, addrLen=2, dataLen=10
```

**厂商设备协议已完全实现！** 🎉

