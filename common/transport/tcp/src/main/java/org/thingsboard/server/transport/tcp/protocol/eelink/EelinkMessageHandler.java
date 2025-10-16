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

import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.adaptor.JsonConverter;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.auth.SessionInfoCreator;
import org.thingsboard.server.common.transport.auth.ValidateDeviceCredentialsResponse;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.tcp.TbTcpTransportComponent;
import org.thingsboard.server.transport.tcp.TcpTransportContext;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkLoginRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkLoginResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkHeartbeatRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkHeartbeatResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkAlarmRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkAlarmResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkDataReportRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkDataReportResponse;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkDataPacket;
import org.thingsboard.server.transport.tcp.session.TcpDeviceSessionContext;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.thingsboard.server.common.transport.service.DefaultTransportService.SESSION_EVENT_MSG_OPEN;

/**
 * Eelink消息处理器
 * 处理Eelink协议的各种消息类型
 */
@Component
@TbTcpTransportComponent
@Slf4j
public class EelinkMessageHandler {
    
    /**
     * 认证设备（使用地址作为认证凭证）
     * 认证成功后会继续处理消息
     * 
     * @param ctx              Netty上下文
     * @param frame            Eelink帧
     * @param context          TCP传输上下文
     * @param deviceSessionCtx 设备会话上下文
     * @param sessionId        会话ID
     * @param frameCode        帧代号，用于认证成功后继续处理
     */
    public void authenticateDevice(ChannelHandlerContext ctx,
                                   EelinkFrame frame,
                                   TcpTransportContext context,
                                   TcpDeviceSessionContext deviceSessionCtx,
                                   UUID sessionId,
                                   byte frameCode) {

        try {
            // 使用地址作为设备标识进行认证
            String deviceToken = frame.getAddressString();
            log.info("[{}] Authenticating device with address: {}", sessionId, deviceToken);

            // 构建认证请求
            TransportProtos.ValidateBasicMqttCredRequestMsg.Builder authRequest = TransportProtos.ValidateBasicMqttCredRequestMsg
                    .newBuilder()
                    .setClientId(sessionId.toString())
                    .setUserName(deviceToken); // 使用地址作为用户名

            TransportService transportService = context.getTransportService();

            // 发送认证请求
            transportService.process(DeviceTransportType.DEFAULT, authRequest.build(),
                    new TransportServiceCallback<>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
                            if (!msg.hasDeviceInfo()) {
                                // 认证失败
                                log.warn("[{}] Device authentication failed for Address: {}", sessionId, deviceToken);
                                sendErrorResponse(ctx, frame, frameCode);
                                ctx.close();
                            } else {
                                // 认证成功
                                log.info("[{}] Device authenticated successfully: {}",
                                        sessionId, msg.getDeviceInfo().getDeviceName());

                                // 设置会话信息
                                deviceSessionCtx.setDeviceInfo(msg.getDeviceInfo());
                                deviceSessionCtx.setDeviceProfile(msg.getDeviceProfile());
                                deviceSessionCtx.setSessionInfo(
                                        SessionInfoCreator.create(msg, context, sessionId));

                                // 发送SESSION_OPEN事件
                                transportService.process(deviceSessionCtx.getSessionInfo(),
                                        SESSION_EVENT_MSG_OPEN,
                                        new TransportServiceCallback<Void>() {
                                            @Override
                                            public void onSuccess(Void v) {
                                                // 注册会话
                                                transportService.registerAsyncSession(
                                                        deviceSessionCtx.getSessionInfo(),
                                                        null); // 暂时不传SessionMsgListener

                                                // 标记已连接
                                                deviceSessionCtx.setConnected(true);
                                                deviceSessionCtx.setChannel(ctx);

                                                log.info("[{}] Device session opened successfully: {}",
                                                        sessionId, msg.getDeviceInfo().getDeviceName());
                                                
                                                // 保存认证的设备地址
                                                deviceSessionCtx.setAuthenticatedDeviceAddress(deviceToken);
                                                log.debug("[{}] Authenticated device address saved: {}", sessionId, deviceToken);
                                                
                                                // 认证成功后，继续处理消息
                                                processMessageAfterAuth(ctx, frame, context, deviceSessionCtx, sessionId, frameCode);
                                            }

                                            @Override
                                            public void onError(Throwable e) {
                                                log.error("[{}] Failed to open session", sessionId, e);
                                                sendErrorResponse(ctx, frame, frameCode);
                                                ctx.close();
                                            }
                                        });
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.error("[{}] Authentication service error", sessionId, e);
                            sendErrorResponse(ctx, frame, frameCode);
                            ctx.close();
                        }
                    });

        } catch (Exception e) {
            log.error("[{}] Failed to authenticate device", sessionId, e);
            sendErrorResponse(ctx, frame, frameCode);
            ctx.close();
        }
    }
    
    /**
     * 认证成功后处理消息
     */
    private void processMessageAfterAuth(ChannelHandlerContext ctx,
                                        EelinkFrame frame,
                                        TcpTransportContext context,
                                        TcpDeviceSessionContext deviceSessionCtx,
                                        UUID sessionId,
                                        byte frameCode) {
        switch (frameCode) {
            case 0x41:  // 设备登陆
                handleLoginRequest(ctx, frame, context, deviceSessionCtx, sessionId);
                break;
            case 0x43:  // 设备心跳
                handleHeartbeatRequest(ctx, frame, context, deviceSessionCtx, sessionId);
                break;
            case 0x46:  // 数据上报
                handleDataReport(ctx, frame, context, deviceSessionCtx, sessionId);
                break;
            case 0x42:  // 警情上报
                handleAlarmReport(ctx, frame, context, deviceSessionCtx, sessionId);
                break;
            default:
                log.warn("[{}] Unknown frame code after authentication: 0x{}", 
                        sessionId, String.format("%02X", frameCode & 0xFF));
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, EelinkFrame frame, byte frameCode) {
        try {
            switch (frameCode) {
                case 0x41:  // 登陆
                    ctx.writeAndFlush(EelinkLoginResponse.error(frame.getAddress(), 
                            EelinkLoginResponse.ERROR_NOT_CONFIGURED).encode(ctx.alloc()));
                    break;
                case 0x43:  // 心跳
                    ctx.writeAndFlush(EelinkHeartbeatResponse.error(frame.getAddress(), 
                            frame.getFunctionCode1(), 
                            EelinkHeartbeatResponse.ERROR_DATA_INVALID).encode(ctx.alloc()));
                    break;
                case 0x42:  // 警情
                    ctx.writeAndFlush(EelinkAlarmResponse.error(frame.getAddress(), 
                            EelinkAlarmResponse.ERROR_DATA_INVALID).encode(ctx.alloc()));
                    break;
                case 0x46:  // 数据上报
                    ctx.writeAndFlush(EelinkDataReportResponse.error(frame.getAddress(), 
                            EelinkDataReportResponse.ERROR_CHANNEL_DATA_INVALID).encode(ctx.alloc()));
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }

    /**
     * 处理设备登陆请求（帧代号0x41）
     * 
     * @param ctx Netty上下文
     * @param frame Eelink帧
     * @param context TCP传输上下文
     * @param deviceSessionCtx 设备会话上下文
     * @param sessionId 会话ID
     */
    public void handleLoginRequest(ChannelHandlerContext ctx,
                                   EelinkFrame frame,
                                   TcpTransportContext context,
                                   TcpDeviceSessionContext deviceSessionCtx,
                                   UUID sessionId) {
        
        log.info("[{}] Processing Eelink login request from device, address={}", 
                sessionId, frame.getAddressString());
        
        try {
            // 发送成功响应
            EelinkLoginResponse response = EelinkLoginResponse.success(
                    frame.getAddress());
            ctx.writeAndFlush(response.encode(ctx.alloc()));
            
        } catch (Exception e) {
            log.error("[{}] Failed to process login request", sessionId, e);
            EelinkLoginResponse response = EelinkLoginResponse.error(
                    frame.getAddress(), 
                    EelinkLoginResponse.ERROR_DATA_INVALID);
            ctx.writeAndFlush(response.encode(ctx.alloc()));
            ctx.close();
        }
    }
    
    /**
     * 处理设备心跳请求（帧代号0x43）
     * 
     * @param ctx Netty上下文
     * @param frame Eelink帧
     * @param context TCP传输上下文
     * @param deviceSessionCtx 设备会话上下文
     * @param sessionId 会话ID
     */
    public void handleHeartbeatRequest(ChannelHandlerContext ctx,
                                       EelinkFrame frame,
                                       TcpTransportContext context,
                                       TcpDeviceSessionContext deviceSessionCtx,
                                       UUID sessionId) {
        
        log.debug("[{}] Processing Eelink heartbeat request from device, address={}", 
                sessionId, frame.getAddressString());
        
        try {
            // 解析心跳请求
            EelinkHeartbeatRequest request = EelinkHeartbeatRequest.parse(frame.getData());
            
            if (request.isLinkHeartbeat()) {
                // 链路心跳
                log.debug("[{}] Link heartbeat received", sessionId);
                
                // 更新设备在线状态
                context.getTransportService().recordActivity(deviceSessionCtx.getSessionInfo());
                
                // 发送链路心跳响应
                EelinkHeartbeatResponse response = EelinkHeartbeatResponse.linkSuccess(
                        frame.getAddress(),
                        frame.getFunctionCode1());
                ctx.writeAndFlush(response.encode(ctx.alloc()));
                
            } else if (request.isStatusHeartbeat()) {
                // 状态心跳
                log.info("[{}] Status heartbeat: mode={}, time={}, report={}s, collect={}s, " +
                        "storage={}s, signal={}, stored={}, unreported={}, battery={}V",
                        sessionId,
                        request.getConnectionMode(),
                        request.getDeviceTime(),
                        request.getReportPeriod(),
                        request.getCollectPeriod(),
                        request.getStoragePeriod(),
                        request.getSignalStrength() & 0xFF,
                        request.getStoredDataCount(),
                        request.getUnreportedCount(),
                        request.getBatteryVoltage());
                
                // 更新设备在线状态
                context.getTransportService().recordActivity(deviceSessionCtx.getSessionInfo());
                
                // 发送设备状态遥测数据到ThingsBoard
                sendHeartbeatTelemetry(request, deviceSessionCtx, context);
                
                // 检查是否需要时间同步（时间差大于1分钟）
                LocalDateTime deviceTime = request.getDeviceTime();
                LocalDateTime currentTime = LocalDateTime.now();
                long timeDiffMinutes = Math.abs(ChronoUnit.MINUTES.between(deviceTime, currentTime));
                
                EelinkHeartbeatResponse response;
                if (timeDiffMinutes > 1) {
                    // 需要时间同步
                    log.info("[{}] Time sync required: device={}, platform={}, diff={}min",
                            sessionId, deviceTime, currentTime, timeDiffMinutes);
                    response = EelinkHeartbeatResponse.statusSuccessWithTimeSync(
                            frame.getAddress(),
                            frame.getFunctionCode1(),
                            currentTime);
                } else {
                    // 无需同步
                    response = EelinkHeartbeatResponse.statusSuccess(
                            frame.getAddress(),
                            frame.getFunctionCode1());
                }
                
                ctx.writeAndFlush(response.encode(ctx.alloc()));
                
            } else {
                log.warn("[{}] Unknown heartbeat type: {}", sessionId, request.getHeartbeatType());
                EelinkHeartbeatResponse response = EelinkHeartbeatResponse.error(
                        frame.getAddress(),
                        frame.getFunctionCode1(),
                        EelinkHeartbeatResponse.ERROR_DATA_INVALID);
                ctx.writeAndFlush(response.encode(ctx.alloc()));
            }
            
        } catch (Exception e) {
            log.error("[{}] Failed to process heartbeat request", sessionId, e);
            EelinkHeartbeatResponse response = EelinkHeartbeatResponse.error(
                    frame.getAddress(),
                    frame.getFunctionCode1(),
                    EelinkHeartbeatResponse.ERROR_DATA_INVALID);
            ctx.writeAndFlush(response.encode(ctx.alloc()));
        }
    }
    
    /**
     * 发送心跳客户端属性到ThingsBoard
     */
    private void sendHeartbeatTelemetry(EelinkHeartbeatRequest request,
                                        TcpDeviceSessionContext deviceSessionCtx,
                                        TcpTransportContext context) {
        try {
            // 构建客户端属性JSON
            StringBuilder attributesJson = new StringBuilder("{");
            attributesJson.append("\"connectionMode\":").append(request.getConnectionMode()).append(",");
            attributesJson.append("\"deviceTime\":\"").append(request.getDeviceTime()).append("\",");
            attributesJson.append("\"reportPeriod\":").append(request.getReportPeriod()).append(",");
            attributesJson.append("\"collectPeriod\":").append(request.getCollectPeriod()).append(",");
            attributesJson.append("\"storagePeriod\":").append(request.getStoragePeriod()).append(",");
            attributesJson.append("\"signalStrength\":").append(request.getSignalStrength() & 0xFF).append(",");
            attributesJson.append("\"storedDataCount\":").append(request.getStoredDataCount()).append(",");
            attributesJson.append("\"unreportedCount\":").append(request.getUnreportedCount()).append(",");
            attributesJson.append("\"batteryVoltage\":").append(request.getBatteryVoltage());
            attributesJson.append("}");

            log.debug("Sending heartbeat client attributes: {}", attributesJson);

            // 将JSON字符串转换为PostAttributeMsg
            TransportProtos.PostAttributeMsg postAttributesMsg = 
                    JsonConverter.convertToAttributesProto(JsonParser.parseString(attributesJson.toString()));
            
            // 通过TransportService发送客户端属性
            context.getTransportService().process(
                    deviceSessionCtx.getSessionInfo(), 
                    postAttributesMsg,
                    new TransportServiceCallback<Void>() {
                        @Override
                        public void onSuccess(Void msg) {
                            log.debug("Successfully saved heartbeat client attributes");
                        }
                        
                        @Override
                        public void onError(Throwable e) {
                            log.error("Failed to save heartbeat client attributes", e);
                        }
                    });
            
        } catch (Exception e) {
            log.error("Failed to send heartbeat client attributes", e);
        }
    }
    
    /**
     * 处理设备警情上报（帧代号0x42）
     * 
     * @param ctx Netty上下文
     * @param frame Eelink帧
     * @param context TCP传输上下文
     * @param deviceSessionCtx 设备会话上下文
     * @param sessionId 会话ID
     */
    public void handleAlarmReport(ChannelHandlerContext ctx,
                                  EelinkFrame frame,
                                  TcpTransportContext context,
                                  TcpDeviceSessionContext deviceSessionCtx,
                                  UUID sessionId) {
        
        log.info("[{}] Processing Eelink alarm report from device, address={}", 
                sessionId, frame.getAddressString());
        
        try {
            // 解析警情上报请求
            EelinkAlarmRequest request = EelinkAlarmRequest.parse(frame.getData());
            
            log.warn("[{}] ALARM REPORT: Type={}, Time={}, DeviceAddr={}", 
                    sessionId,
                    request.getAlarmTypeName(),
                    request.getAlarmTime(),
                    request.getAlarmDeviceAddressString());
                        
            // 更新设备活动状态
            // context.getTransportService().recordActivity(deviceSessionCtx.getSessionInfo());
                        
            // 发送成功响应
            EelinkAlarmResponse response = EelinkAlarmResponse.success(frame.getAddress());
            ctx.writeAndFlush(response.encode(ctx.alloc()));
            
            log.info("[{}] Alarm report processed successfully", sessionId);
            
        } catch (Exception e) {
            log.error("[{}] Failed to process alarm report", sessionId, e);
            EelinkAlarmResponse response = EelinkAlarmResponse.error(
                    frame.getAddress(),
                    EelinkAlarmResponse.ERROR_DATA_INVALID);
            ctx.writeAndFlush(response.encode(ctx.alloc()));
        }
    }
    
    /**
     * 处理设备数据上报（帧代号0x46）
     * 
     * @param ctx Netty上下文
     * @param frame Eelink帧
     * @param context TCP传输上下文
     * @param deviceSessionCtx 设备会话上下文
     * @param sessionId 会话ID
     */
    public void handleDataReport(ChannelHandlerContext ctx,
                                 EelinkFrame frame,
                                 TcpTransportContext context,
                                 TcpDeviceSessionContext deviceSessionCtx,
                                 UUID sessionId) {
        
        log.info("[{}] Processing Eelink data report from device, address={}", 
                sessionId, frame.getAddressString());
        
        try {
            // 解析数据上报请求
            EelinkDataReportRequest request = EelinkDataReportRequest.parse(frame.getData());
            
            log.info("[{}] DATA REPORT: encrypted={}, packetCount={}, totalChannels={}", 
                    sessionId,
                    request.isEncrypted(),
                    request.getPacketCount(),
                    request.getTotalChannelCount());
            
            // 检查是否为加密数据（当前不支持）
            if (request.isEncrypted()) {
                log.warn("[{}] Encrypted data not supported", sessionId);
                EelinkDataReportResponse response = EelinkDataReportResponse.error(
                        frame.getAddress(),
                        EelinkDataReportResponse.ERROR_CHANNEL_DATA_INVALID);
                ctx.writeAndFlush(response.encode(ctx.alloc()));
                return;
            }
            
            // 处理所有数据包
            for (int i = 0; i < request.getPacketCount(); i++) {
                EelinkDataPacket packet = request.getDataPackets().get(i);
                
                log.info("[{}] Data packet {}: time={}, channels={}, addr={}, storage={}/{}", 
                        sessionId,
                        i + 1,
                        packet.getCollectTime(),
                        packet.getChannelCount(),
                        packet.getDataAddressString(),
                        packet.getStorageTypeName(),
                        packet.getStorageSequence());
                
                // 发送数据包遥测数据到ThingsBoard
                sendDataPacketTelemetry(packet, deviceSessionCtx, context);
            }
            
            // 更新设备活动状态
            context.getTransportService().recordActivity(deviceSessionCtx.getSessionInfo());
            
            // 发送成功响应
            EelinkDataReportResponse response = EelinkDataReportResponse.success(frame.getAddress());
            ctx.writeAndFlush(response.encode(ctx.alloc()));
            
            log.info("[{}] Data report processed successfully: {} packets", sessionId, request.getPacketCount());
            
        } catch (Exception e) {
            log.error("[{}] Failed to process data report", sessionId, e);
            EelinkDataReportResponse response = EelinkDataReportResponse.error(
                    frame.getAddress(),
                    EelinkDataReportResponse.ERROR_CHANNEL_DATA_INVALID);
            ctx.writeAndFlush(response.encode(ctx.alloc()));
        }
    }
    
    /**
     * 发送数据包遥测数据到ThingsBoard
     */
    private void sendDataPacketTelemetry(EelinkDataPacket packet,
                                        TcpDeviceSessionContext deviceSessionCtx,
                                        TcpTransportContext context) {
        try {
            // 构建遥测数据JSON
            StringBuilder telemetryJson = new StringBuilder("{");
            // telemetryJson.append("\"collectTime\":\"").append(packet.getCollectTime()).append("\",");
            // telemetryJson.append("\"dataAddress\":\"").append(packet.getDataAddressString()).append("\",");
            // telemetryJson.append("\"storageType\":\"").append(packet.getStorageTypeName()).append("\",");
            // telemetryJson.append("\"storageSequence\":").append(packet.getStorageSequence()).append(",");
            
            // 按远程测控终端协议定义将通道序号映射为具体点位含义
            // 压力
            // 水位
            // 阀门电压
            // 电池电压
            // 开关状态
            // 设备信息
            // 瞬时流量
            // 累计流量
            // 阀门开度
            // 阀门开度2
            // 阀门开度3
            // 瞬时流量2
            // 累计流量2
            // 瞬时流量3
            // 累计流量3
            String[] channelNames = {
                "pressure",          // 0
                "waterLevel",        // 1
                "valveVoltage",      // 2
                "batteryVoltage",    // 3
                "switchStatus",      // 4
                "deviceInfo",        // 5
                "instantFlow1",      // 6
                "totalFlow1",        // 7
                "valveOpening1",     // 8
                "valveOpening2",     // 9
                "valveOpening3",     // 10
                "instantFlow2",      // 11
                "totalFlow2",        // 12
                "instantFlow3",      // 13
                "totalFlow3"         // 14
            };

            for (int i = 0; i < channelNames.length; i++) {
                Float value = packet.getChannelData(i);
                if (value == null) {
                    break;
                }

                if (i > 0) {
                    telemetryJson.append(",");
                }
                telemetryJson.append("\"").append(channelNames[i]).append("\":").append(value);
            }
            
            telemetryJson.append("}");

            log.info("Sending data packet telemetry: {}", telemetryJson);

            // 将JSON字符串转换为PostTelemetryMsg
            TransportProtos.PostTelemetryMsg postTelemetryMsg = 
                    JsonConverter.convertToTelemetryProto(JsonParser.parseString(telemetryJson.toString()));
            
            // 通过TransportService发送遥测数据
            context.getTransportService().process(
                    deviceSessionCtx.getSessionInfo(), 
                    postTelemetryMsg,
                    new TransportServiceCallback<Void>() {
                        @Override
                        public void onSuccess(Void msg) {
                            log.debug("Successfully saved data packet telemetry");
                        }
                        
                        @Override
                        public void onError(Throwable e) {
                            log.error("Failed to save data packet telemetry", e);
                        }
                    });
            
        } catch (Exception e) {
            log.error("Failed to send data packet telemetry", e);
        }
    }
    
    /**
     * 获取通道名称
     * 
     * 远程测控终端通道定义：
     * 0-压力, 1-水位, 2-阀门电压, 3-电池电压, 4-开关状态, 5-设备信息,
     * 6-瞬时流量, 7-累计流量, 8-阀门开度, 9-阀门开度2, 10-阀门开度3
     * 
     * 泵房通道定义（15通道）：
     * 0-压力, 1-水位, 2-阀门电压, 3-开关状态, 4-设备信息, 5-瞬时流量, 6-累计流量,
     * 7-A相电压, 8-B相电压, 9-C相电压, 10-A相电流, 11-B相电流, 12-C相电流,
     * 13-电量, 14-电功率
     */
    private String getChannelName(int index) {
        // 使用远程测控终端通道定义（支持更多设备类型）
        switch (index) {
            case 0: return "pressure";           // 压力
            case 1: return "waterLevel";         // 水位
            case 2: return "valveVoltage";       // 阀门电压
            case 3: return "batteryVoltage";     // 电池电压
            case 4: return "switchStatus";       // 开关状态
            case 5: return "deviceInfo";         // 设备信息
            case 6: return "instantFlow";        // 瞬时流量
            case 7: return "totalFlow";          // 累计流量
            case 8: return "valveOpening1";      // 阀门开度1
            case 9: return "valveOpening2";      // 阀门开度2
            case 10: return "valveOpening3";     // 阀门开度3
            // 泵房专用通道（索引7-14）
            case 11: return "voltageB";          // B相电压
            case 12: return "voltageC";          // C相电压
            case 13: return "currentA";          // A相电流
            case 14: return "currentB";          // B相电流
            case 15: return "currentC";          // C相电流
            case 16: return "electricEnergy";    // 电量
            case 17: return "electricPower";     // 电功率
            default: return "channel" + index;   // 通道N
        }
    }
}

