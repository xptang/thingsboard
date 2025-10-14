# Thingsboard TCP Transport

这是一个为Thingsboard IoT平台设计的TCP传输模块，支持字符串和二进制两种协议格式。

## 功能特性

- **双协议支持**：支持JSON（字符串）和Protobuf（二进制）两种数据格式
- **基于Netty**：使用高性能的Netty框架构建
- **SSL/TLS支持**：可选的SSL/TLS加密传输
- **会话管理**：完整的设备会话管理
- **协议适配器**：灵活的协议适配器架构

## 配置参数

在 `application.yml` 或 `thingsboard.yml` 中添加以下配置：

```yaml
transport:
  tcp:
    enabled: true
    bind_address: 0.0.0.0
    bind_port: 8883
    ssl:
      enabled: false
      bind_address: 0.0.0.0
      bind_port: 8884
    netty:
      leak_detector_level: DISABLED
      boss_group_thread_count: 1
      worker_group_thread_count: 12
      so_keep_alive: true
      max_payload_size: 65536
    msg_queue_size_per_device_limit: 100
    timeout: 10000
    disconnect_timeout: 1000
```

## 协议格式

### 1. 认证

客户端连接后首先需要进行认证：

#### JSON格式（字符串）
```
AUTH:YOUR_DEVICE_TOKEN\n
```

#### Protobuf格式（二进制）
```
AUTH:YOUR_DEVICE_TOKEN:PROTO\n
```

成功响应：
```
AUTH_OK\n
```

失败响应：
```
AUTH_FAILED\n
```

### 2. 发送遥测数据

#### JSON格式
```
TELEMETRY:{"temperature":25.5,"humidity":60}\n
```

响应：
```
TELEMETRY_OK\n
```

#### Protobuf格式
```
TELEMETRY:<protobuf_binary_data>\n
```

### 3. 发送属性数据

#### JSON格式
```
ATTRIBUTES:{"model":"T1000","firmwareVersion":"1.0.0"}\n
```

响应：
```
ATTRIBUTES_OK\n
```

#### Protobuf格式
```
ATTRIBUTES:<protobuf_binary_data>\n
```

### 4. RPC响应

#### JSON格式
```
RPC_RESPONSE:{"id":1,"data":"response_data"}\n
```

响应：
```
RPC_RESPONSE_OK\n
```

### 5. 接收服务器消息

#### 属性更新
```
ATTRIBUTES_UPDATE:<json_or_protobuf_data>\n
```

#### RPC请求
```
RPC_REQUEST:<json_or_protobuf_data>\n
```

#### 属性响应
```
ATTRIBUTES_RESPONSE:<json_or_protobuf_data>\n
```

## 使用示例

### Python客户端示例（JSON格式）

```python
import socket
import json

# 连接到服务器
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect(('localhost', 8883))

# 认证
auth_msg = "AUTH:YOUR_DEVICE_TOKEN\n"
sock.send(auth_msg.encode())
response = sock.recv(1024).decode()
print(f"Auth response: {response}")

if response.strip() == "AUTH_OK":
    # 发送遥测数据
    telemetry = {
        "temperature": 25.5,
        "humidity": 60
    }
    telemetry_msg = f"TELEMETRY:{json.dumps(telemetry)}\n"
    sock.send(telemetry_msg.encode())
    response = sock.recv(1024).decode()
    print(f"Telemetry response: {response}")
    
    # 发送属性
    attributes = {
        "model": "T1000",
        "firmwareVersion": "1.0.0"
    }
    attr_msg = f"ATTRIBUTES:{json.dumps(attributes)}\n"
    sock.send(attr_msg.encode())
    response = sock.recv(1024).decode()
    print(f"Attributes response: {response}")

sock.close()
```

### Java客户端示例（JSON格式）

```java
import java.io.*;
import java.net.Socket;

public class TcpClient {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 8883);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            // 认证
            out.println("AUTH:YOUR_DEVICE_TOKEN");
            String response = in.readLine();
            System.out.println("Auth response: " + response);
            
            if ("AUTH_OK".equals(response)) {
                // 发送遥测数据
                String telemetry = "TELEMETRY:{\"temperature\":25.5,\"humidity\":60}";
                out.println(telemetry);
                response = in.readLine();
                System.out.println("Telemetry response: " + response);
                
                // 发送属性
                String attributes = "ATTRIBUTES:{\"model\":\"T1000\",\"firmwareVersion\":\"1.0.0\"}";
                out.println(attributes);
                response = in.readLine();
                System.out.println("Attributes response: " + response);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

## 项目结构

```
tcp/
├── pom.xml
├── README.md
└── src/main/java/org/thingsboard/server/transport/tcp/
    ├── TbTcpTransportComponent.java          # 组件注解
    ├── TcpTransportContext.java              # 上下文类
    ├── TcpTransportService.java              # 主服务类
    ├── TcpTransportHandler.java              # 消息处理器
    ├── TcpTransportServerInitializer.java    # Netty初始化器
    ├── adaptors/
    │   ├── TcpTransportAdaptor.java          # 适配器接口
    │   ├── StringTcpAdaptor.java             # 字符串/JSON适配器
    │   └── BinaryTcpAdaptor.java             # 二进制/Protobuf适配器
    └── session/
        └── TcpDeviceSessionContext.java      # 会话上下文
```

## 编译和集成

1. 确保父项目 `pom.xml` 中包含此模块：
```xml
<module>common/transport/tcp</module>
```

2. 编译项目：
```bash
mvn clean install
```

3. 在应用配置中启用TCP传输：
```yaml
transport:
  tcp:
    enabled: true
```

## 注意事项

1. 所有消息必须以换行符 `\n` 结尾
2. 认证必须是连接后的第一条消息
3. 认证成功后才能发送业务数据
4. 支持长连接，建议启用 SO_KEEPALIVE
5. 默认空闲超时时间为5分钟

## 扩展开发

### 添加新的消息类型

1. 在 `TcpTransportHandler.processDataMessage()` 中添加新的消息类型处理
2. 在适配器中实现相应的转换方法
3. 更新协议文档

### 自定义协议适配器

实现 `TcpTransportAdaptor` 接口并注册为Spring Bean：

```java
@Component
@TbTcpTransportComponent
public class CustomTcpAdaptor implements TcpTransportAdaptor {
    // 实现接口方法
}
```

## 许可证

Apache License 2.0

