# TCP二进制协议文档

## 📦 二进制帧格式

```
+--------+--------+---------+-----+---------+
| Header | Length | Payload | CRC | Footer  |
+--------+--------+---------+-----+---------+
```

### 字段说明

1. **Header（包头）**
   - 特殊二进制标识符
   - 默认值：`0xAA55` (2字节)
   - 可配置长度和内容

2. **Length（长度）**
   - Payload的字节数
   - 可配置1、2或4字节
   - 可配置大端或小端字节序
   - 默认：2字节，大端序

3. **Payload（负载）**
   - 实际数据内容（JSON字符串）
   - 可变长度

4. **CRC（校验）**
   - 对Payload的校验值
   - 支持CRC16、CRC32或无校验
   - 可配置字节序
   - 默认：CRC16，大端序

5. **Footer（包尾）**
   - 特殊二进制标识符
   - 默认值：`0x55AA` (2字节)
   - 可配置长度和内容

## ⚙️ 配置

在 `thingsboard.yml` 中：

```yaml
transport:
  tcp:
    enabled: true
    protocol_type: binary    # 启用二进制协议
    
    binary:
      # 包头（16进制）
      header: "0xAA55"
      
      # 包尾（16进制）
      footer: "0x55AA"
      
      # 长度字段字节数：1, 2, 或 4
      length_bytes: 2
      
      # 长度字节序：big_endian 或 little_endian
      length_endian: big_endian
      
      # CRC类型：crc16, crc32, 或 none
      crc_type: crc16
      
      # CRC字节序：big_endian 或 little_endian
      crc_endian: big_endian
```

## 📝 示例帧格式

### 默认配置下的帧结构

发送 `AUTH:TOKEN` 的二进制帧：

```
Offset  | Field          | Hex Value        | Description
--------|----------------|------------------|-------------
0-1     | Header         | AA 55            | 固定包头
2-3     | Length         | 00 0A            | 长度=10字节(big-endian)
4-13    | Payload        | 41 55 54 48...   | "AUTH:TOKEN"
14-15   | CRC16          | XX XX            | CRC16校验
16-17   | Footer         | 55 AA            | 固定包尾
```

### 完整示例（HEX）

```
认证帧：
AA 55                    # Header
00 0A                    # Length = 10
41 55 54 48 3A 54 4F 4B 45 4E   # "AUTH:TOKEN"
C3 4F                    # CRC16 (示例)
55 AA                    # Footer

遥测帧：
AA 55                    # Header
00 1E                    # Length = 30
54 45 4C 45...           # "TELEMETRY:{\"temperature\":25.5}"
XX XX                    # CRC16
55 AA                    # Footer
```

## 💻 实现细节

### CRC计算

#### CRC16-CCITT
- 多项式：0x1021 (x^16 + x^12 + x^5 + 1)
- 初始值：0xFFFF
- 计算范围：仅Payload部分

#### CRC32
- 标准CRC32算法
- 计算范围：仅Payload部分

### 字节序

#### Big-Endian（大端序，默认）
```
Length = 0x1234
Bytes: [0x12, 0x34]
```

#### Little-Endian（小端序）
```
Length = 0x1234
Bytes: [0x34, 0x12]
```

## 🔧 客户端实现示例

### Python客户端（二进制协议）

```python
import socket
import struct

class BinaryTcpClient:
    HEADER = bytes([0xAA, 0x55])
    FOOTER = bytes([0x55, 0xAA])
    
    def __init__(self, host, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((host, port))
    
    def calculate_crc16(self, data):
        crc = 0xFFFF
        for byte in data:
            crc ^= byte << 8
            for _ in range(8):
                if crc & 0x8000:
                    crc = (crc << 1) ^ 0x1021
                else:
                    crc = crc << 1
                crc &= 0xFFFF
        return crc
    
    def send_frame(self, payload):
        # Payload as bytes
        if isinstance(payload, str):
            payload = payload.encode('utf-8')
        
        # Build frame
        frame = bytearray()
        frame.extend(self.HEADER)                    # Header
        frame.extend(struct.pack('>H', len(payload))) # Length (big-endian)
        frame.extend(payload)                         # Payload
        
        # Calculate and add CRC
        crc = self.calculate_crc16(payload)
        frame.extend(struct.pack('>H', crc))          # CRC16 (big-endian)
        
        frame.extend(self.FOOTER)                     # Footer
        
        # Send
        self.sock.sendall(frame)
    
    def receive_frame(self):
        # Read header
        header = self.sock.recv(2)
        if header != self.HEADER:
            raise ValueError("Invalid header")
        
        # Read length
        length_bytes = self.sock.recv(2)
        length = struct.unpack('>H', length_bytes)[0]
        
        # Read payload
        payload = self.sock.recv(length)
        
        # Read CRC
        crc_bytes = self.sock.recv(2)
        received_crc = struct.unpack('>H', crc_bytes)[0]
        
        # Verify CRC
        calculated_crc = self.calculate_crc16(payload)
        if calculated_crc != received_crc:
            raise ValueError("CRC mismatch")
        
        # Read footer
        footer = self.sock.recv(2)
        if footer != self.FOOTER:
            raise ValueError("Invalid footer")
        
        return payload.decode('utf-8')
    
    def authenticate(self, token):
        self.send_frame(f"AUTH:{token}")
        response = self.receive_frame()
        return response.strip() == "AUTH_OK"
    
    def send_telemetry(self, data):
        import json
        self.send_frame(f"TELEMETRY:{json.dumps(data)}")
        response = self.receive_frame()
        return response.strip() == "TELEMETRY_OK"

# 使用示例
client = BinaryTcpClient('localhost', 8883)
if client.authenticate('YOUR_TOKEN'):
    print("Authenticated!")
    client.send_telemetry({"temperature": 25.5, "humidity": 60})
```

### Java客户端（二进制协议）

```java
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BinaryTcpClient {
    private static final byte[] HEADER = {(byte)0xAA, (byte)0x55};
    private static final byte[] FOOTER = {(byte)0x55, (byte)0xAA};
    
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    
    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }
    
    public void sendFrame(byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        
        // Header
        frame.write(HEADER);
        
        // Length (2 bytes, big-endian)
        frame.write((payload.length >> 8) & 0xFF);
        frame.write(payload.length & 0xFF);
        
        // Payload
        frame.write(payload);
        
        // CRC16
        int crc = calculateCrc16(payload);
        frame.write((crc >> 8) & 0xFF);
        frame.write(crc & 0xFF);
        
        // Footer
        frame.write(FOOTER);
        
        out.write(frame.toByteArray());
        out.flush();
    }
    
    public String receiveFrame() throws IOException {
        // Read header
        byte[] header = in.readNBytes(2);
        
        // Read length
        int length = (in.read() << 8) | in.read();
        
        // Read payload
        byte[] payload = in.readNBytes(length);
        
        // Read CRC
        int receivedCrc = (in.read() << 8) | in.read();
        
        // Verify CRC
        int calculatedCrc = calculateCrc16(payload);
        if (calculatedCrc != receivedCrc) {
            throw new IOException("CRC mismatch");
        }
        
        // Read footer
        byte[] footer = in.readNBytes(2);
        
        return new String(payload);
    }
    
    private int calculateCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
            }
        }
        return crc & 0xFFFF;
    }
    
    public boolean authenticate(String token) throws IOException {
        sendFrame(("AUTH:" + token).getBytes());
        String response = receiveFrame();
        return "AUTH_OK".equals(response.trim());
    }
}
```

## 🧪 测试工具

### 16进制查看器

使用 `hexdump` 查看二进制帧：

```bash
# 发送数据并查看
echo "AUTH:TOKEN" | xxd
```

### Python测试脚本

```python
#!/usr/bin/env python3
import socket
import struct

def send_test_frame(host='localhost', port=8883):
    payload = b"AUTH:TEST_TOKEN"
    
    # Build frame
    frame = bytearray()
    frame.extend(bytes([0xAA, 0x55]))           # Header
    frame.extend(struct.pack('>H', len(payload))) # Length
    frame.extend(payload)                        # Payload
    
    # CRC16
    crc = 0xFFFF
    for byte in payload:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = (crc << 1) ^ 0x1021
            else:
                crc = crc << 1
            crc &= 0xFFFF
    
    frame.extend(struct.pack('>H', crc))         # CRC
    frame.extend(bytes([0x55, 0xAA]))           # Footer
    
    # Print frame
    print("Binary Frame (hex):")
    print(' '.join(f'{b:02X}' for b in frame))
    print()
    
    # Send to server
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((host, port))
    sock.sendall(frame)
    
    # Receive response
    response = sock.recv(1024)
    print("Response (hex):")
    print(' '.join(f'{b:02X}' for b in response))
    sock.close()

if __name__ == '__main__':
    send_test_frame()
```

## 📊 配置示例

### 示例1：默认配置（CRC16）

```yaml
transport:
  tcp:
    protocol_type: binary
    binary:
      header: "0xAA55"
      footer: "0x55AA"
      length_bytes: 2
      length_endian: big_endian
      crc_type: crc16
      crc_endian: big_endian
```

### 示例2：无CRC校验

```yaml
transport:
  tcp:
    protocol_type: binary
    binary:
      header: "0xFF00"
      footer: "0x00FF"
      length_bytes: 4
      length_endian: little_endian
      crc_type: none
```

### 示例3：CRC32校验

```yaml
transport:
  tcp:
    protocol_type: binary
    binary:
      header: "0xDEADBEEF"  # 可以是任意长度
      footer: "0xBEEFDEAD"
      length_bytes: 4
      crc_type: crc32
```

## 🔍 调试技巧

### 1. 查看原始字节

```bash
# 使用tcpdump捕获
sudo tcpdump -i lo -X port 8883

# 使用wireshark
wireshark -i lo -f "port 8883"
```

### 2. 手动构造帧

```python
# 使用Python交互式构造
import struct
header = bytes([0xAA, 0x55])
payload = b"AUTH:TOKEN"
length = struct.pack('>H', len(payload))
# ...
```

### 3. 验证CRC

```bash
# 使用在线CRC计算器验证
# 或使用命令行工具
echo -n "AUTH:TOKEN" | crc16
```

## ⚠️ 注意事项

1. **所有多字节字段都要注意字节序**
2. **CRC只计算Payload部分，不包括其他字段**
3. **Header和Footer可以是任意长度的十六进制串**
4. **支持的十六进制格式：`0xAA55`, `AA55`, `AA 55`, `0xAA 0x55`**
5. **Length字段不包括Header、Length、CRC和Footer的长度**

## 🎯 优势

- ✅ **可靠性**：CRC校验确保数据完整性
- ✅ **灵活性**：所有参数都可配置
- ✅ **效率**：二进制传输效率高
- ✅ **兼容性**：同时支持字符串和二进制协议

## 📈 性能对比

| 指标 | 字符串协议 | 二进制协议 |
|-----|-----------|-----------|
| 帧开销 | ~1字节(\\n) | 8-12字节（默认配置） |
| 可靠性 | 低 | 高（CRC校验） |
| 调试难度 | 低 | 中 |
| 传输效率 | 中 | 高 |
| 适用场景 | 开发/测试 | 生产环境 |

## 🛠️ 故障排查

### 问题：CRC校验失败

- 确认CRC类型配置正确
- 确认字节序配置正确
- 验证CRC计算只针对Payload
- 检查payload数据是否完整

### 问题：帧解析失败

- 检查Header/Footer配置
- 确认Length字段字节数正确
- 验证字节序设置
- 使用hexdump查看原始数据

### 问题：找不到帧边界

- Header必须唯一，避免在payload中出现
- 可以使用更长的Header（如4字节）提高唯一性
- 建议Header使用特殊模式，如 `0xDEADBEEF`

## 🔄 从字符串协议迁移

1. 修改配置：`protocol_type: string` → `binary`
2. 重启服务
3. 更新客户端代码使用二进制帧格式
4. 测试验证

## 📚 相关资源

- CRC16-CCITT算法：https://en.wikipedia.org/wiki/Cyclic_redundancy_check
- Netty ByteToMessageDecoder：https://netty.io/4.1/api/io/netty/handler/codec/ByteToMessageDecoder.html

