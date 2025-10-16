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
package org.thingsboard.server.transport.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.adaptor.AdaptorException;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.TransportPayloadType;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.auth.SessionInfoCreator;
import org.thingsboard.server.common.transport.auth.ValidateDeviceCredentialsResponse;
import org.thingsboard.server.common.transport.service.SessionMetaData;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.tcp.adaptors.TcpTransportAdaptor;
import org.thingsboard.server.transport.tcp.session.TcpDeviceSessionContext;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrame;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.thingsboard.server.common.transport.service.DefaultTransportService.SESSION_EVENT_MSG_CLOSED;
import static org.thingsboard.server.common.transport.service.DefaultTransportService.SESSION_EVENT_MSG_OPEN;

/**
 * TCP Transport Handler
 */
@Slf4j
public class TcpTransportHandler extends ChannelInboundHandlerAdapter implements GenericFutureListener<Future<? super Void>>, SessionMsgListener {

    private final UUID sessionId;
    private final TcpTransportContext context;
    private final TransportService transportService;
    private final SslHandler sslHandler;
    private final TcpDeviceSessionContext deviceSessionCtx;
    private final boolean useBinaryProtocol;

    private volatile InetSocketAddress address;
    private volatile boolean authenticated = false;
    private volatile String loginDeviceAddress = null;  // 登录时使用的设备地址（ACCESS TOKEN）

    public TcpTransportHandler(TcpTransportContext context, SslHandler sslHandler, boolean useBinaryProtocol) {
        this.sessionId = UUID.randomUUID();
        this.context = context;
        this.transportService = context.getTransportService();
        this.sslHandler = sslHandler;
        this.deviceSessionCtx = new TcpDeviceSessionContext(sessionId, context);
        this.useBinaryProtocol = useBinaryProtocol;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        context.channelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        context.channelUnregistered();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        log.trace("[{}] Processing msg: {}", sessionId, msg);
        if (address == null) {
            address = (InetSocketAddress) ctx.channel().remoteAddress();
        }
        
        try {
            if (useBinaryProtocol && msg instanceof EelinkFrame) {
                // 处理Eelink二进制协议消息
                processEelinkFrame(ctx, (EelinkFrame) msg);
            } else if (msg instanceof ByteBuf) {
                // 处理字符串协议消息
                ByteBuf byteBuf = (ByteBuf) msg;
                
                if (!authenticated) {
                    processAuthMessage(ctx, byteBuf);
                } else {
                    processDataMessage(ctx, byteBuf);
                }
            }
        } catch (Exception e) {
            log.error("[{}] Failed to process message", sessionId, e);
            ctx.close();
        } finally {
            ReferenceCountUtil.safeRelease(msg);
        }
    }
    
    /**
     * 处理Eelink二进制协议帧
     * 所有包都会触发认证检查，认证凭证是设备地址
     */
    private void processEelinkFrame(ChannelHandlerContext ctx, 
                                    EelinkFrame frame) {
        byte frameCode = frame.getFrameCode();
        String deviceAddress = frame.getAddressString();
        
        log.info("[{}] Processing Eelink frame, frameCode=0x{}, deviceAddr={}", 
                sessionId, 
                String.format("%02X", frameCode & 0xFF),
                deviceAddress);

        // 检查是否已认证
        if (!isEelinkDeviceAuthenticated()) {
            // 未认证，触发认证流程（异步），认证成功后会在回调中处理消息
            log.info("[{}] Device not authenticated, triggering authentication for address: {}", 
                    sessionId, deviceAddress);
            context.getEelinkMessageHandler().authenticateDevice(
                    ctx, frame, context, deviceSessionCtx, sessionId, 
                    frameCode);  // 传入frameCode，认证成功后继续处理
            return;  // 认证是异步的，在回调中继续处理
        }
        
        // 已认证，检查设备地址是否匹配
        String authenticatedDeviceAddress = getAuthenticatedDeviceAddress();
        if (authenticatedDeviceAddress != null && !authenticatedDeviceAddress.equals(deviceAddress)) {
            log.warn("[{}] Device address mismatch! Authenticated device: {}, Current packet: {}. " +
                    "All packets in a session must come from the same device!", 
                    sessionId, authenticatedDeviceAddress, deviceAddress);
            ctx.close();  // 关闭连接，地址不匹配
            return;
        }
        
        // 已认证且地址匹配，分发消息
        dispatchEelinkMessage(ctx, frame, frameCode);
    }
    
    /**
     * 分发Eelink消息到具体的处理方法
     */
    private void dispatchEelinkMessage(ChannelHandlerContext ctx, 
                                      EelinkFrame frame,
                                      byte frameCode) {
        // 确保登录设备地址已保存（用于后续地址匹配检查）
        if (loginDeviceAddress == null) {
            loginDeviceAddress = frame.getAddressString();
            log.debug("[{}] Device address saved: {}", sessionId, loginDeviceAddress);
        }
        
        // 根据帧代号分发消息
        switch (frameCode) {
            case 0x41:  // 设备登陆
                handleEelinkLogin(ctx, frame);
                break;
                
            case 0x43:  // 设备心跳
                handleEelinkHeartbeat(ctx, frame);
                break;
                
            case 0x46:  // 数据上报
                handleEelinkDataReport(ctx, frame);
                break;
                
            case 0x42:  // 警情上报
                handleEelinkAlarm(ctx, frame);
                break;
                
            default:
                log.warn("[{}] Unknown Eelink frame code: 0x{} data: {}", 
                        sessionId, Integer.toHexString(frameCode & 0xFF), frame.getDataString());
        }
    }
    
    /**
     * 检查Eelink设备是否已完成认证
     * 此方法用于确保登录包是第一个包，只有认证通过后才能处理其他类型的包
     * 
     * @return true表示已认证，false表示未认证
     */
    private boolean isEelinkDeviceAuthenticated() {
        // 检查会话信息是否已设置（登录成功后会设置）
        if (deviceSessionCtx.getSessionInfo() == null) {
            return false;
        }
        
        // 检查连接状态是否已标记为已连接（登录成功后会设置为true）
        if (!deviceSessionCtx.isConnected()) {
            return false;
        }
        
        // 检查设备信息是否已设置
        if (deviceSessionCtx.getDeviceInfo() == null) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取已认证设备的地址
     * 返回登录时使用的设备地址（ACCESS TOKEN）
     * 
     * @return 已认证设备的地址，如果未认证则返回null
     */
    private String getAuthenticatedDeviceAddress() {
        if (!isEelinkDeviceAuthenticated()) {
            return null;
        }
        
        // 优先返回从 deviceSessionCtx 中保存的认证设备地址
        if (deviceSessionCtx.getAuthenticatedDeviceAddress() != null) {
            return deviceSessionCtx.getAuthenticatedDeviceAddress();
        }
        
        // 其次返回保存的登录设备地址
        return loginDeviceAddress;
    }
    
    /**
     * 处理Eelink设备登陆
     */
    private void handleEelinkLogin(ChannelHandlerContext ctx, 
                                   EelinkFrame frame) {
        if (context.getEelinkMessageHandler() != null) {
            // 保存登录时使用的设备地址（ACCESS TOKEN）
            loginDeviceAddress = frame.getAddressString();
            log.debug("[{}] Login device address saved: {}", sessionId, loginDeviceAddress);
            
            context.getEelinkMessageHandler().handleLoginRequest(
                    ctx, frame, context, deviceSessionCtx, sessionId);
        } else {
            log.error("[{}] EelinkMessageHandler not available", sessionId);
            ctx.close();
        }
    }
    
    /**
     * 处理Eelink设备心跳
     */
    private void handleEelinkHeartbeat(ChannelHandlerContext ctx, 
                                       EelinkFrame frame) {
        if (context.getEelinkMessageHandler() != null) {
            context.getEelinkMessageHandler().handleHeartbeatRequest(
                    ctx, frame, context, deviceSessionCtx, sessionId);
        } else {
            log.error("[{}] EelinkMessageHandler not available", sessionId);
            ctx.close();
        }
    }
    
    /**
     * 处理Eelink设备警情上报
     */
    private void handleEelinkAlarm(ChannelHandlerContext ctx,
                                   EelinkFrame frame) {
        if (context.getEelinkMessageHandler() != null) {
            context.getEelinkMessageHandler().handleAlarmReport(
                    ctx, frame, context, deviceSessionCtx, sessionId);
        } else {
            log.error("[{}] EelinkMessageHandler not available", sessionId);
            ctx.close();
        }
    }
    
    /**
     * 处理Eelink设备数据上报
     */
    private void handleEelinkDataReport(ChannelHandlerContext ctx,
                                       EelinkFrame frame) {
        if (context.getEelinkMessageHandler() != null) {
            context.getEelinkMessageHandler().handleDataReport(
                    ctx, frame, context, deviceSessionCtx, sessionId);
        } else {
            log.error("[{}] EelinkMessageHandler not available", sessionId);
            ctx.close();
        }
    }

    private void processAuthMessage(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        try {
            String authData = byteBuf.toString(StandardCharsets.UTF_8);
            log.info("[{}] Processing auth message: {}", sessionId, authData);
            
            // Parse auth data - expecting format: "AUTH:deviceToken"
            String[] parts = authData.split(":");
            if (parts.length < 2 || !"AUTH".equals(parts[0])) {
                log.warn("[{}] Invalid auth message format: {}", sessionId, authData);
                sendResponse(ctx, "AUTH_FAILED:Invalid format\n");
                ctx.close();
                return;
            }
            
            String deviceToken = parts[1];
            log.info("[{}] Authenticating with token: {}", sessionId, deviceToken);
            
            TransportProtos.ValidateBasicMqttCredRequestMsg.Builder request = 
                    TransportProtos.ValidateBasicMqttCredRequestMsg.newBuilder()
                    .setClientId(sessionId.toString())
                    .setUserName(deviceToken);
            
            transportService.process(DeviceTransportType.DEFAULT, request.build(),
                    new TransportServiceCallback<>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
                            onValidateDeviceResponse(msg, ctx);
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.warn("[{}] Failed to process credentials", address, e);
                            sendResponse(ctx, "AUTH_FAILED:Server error\n");
                            ctx.close();
                        }
                    });
            
        } catch (Exception e) {
            log.error("[{}] Failed to process auth message", sessionId, e);
            sendResponse(ctx, "AUTH_FAILED:Exception\n");
            ctx.close();
        }
    }

    private void onValidateDeviceResponse(ValidateDeviceCredentialsResponse msg, 
                                         ChannelHandlerContext ctx) {
        if (!msg.hasDeviceInfo()) {
            context.onAuthFailure(address);
            log.warn("[{}] Authentication failed - invalid credentials", sessionId);
            sendResponse(ctx, "AUTH_FAILED:Invalid credentials\n");
            ctx.close();
        } else {
            context.onAuthSuccess(address);
            deviceSessionCtx.setDeviceInfo(msg.getDeviceInfo());
            deviceSessionCtx.setDeviceProfile(msg.getDeviceProfile());
            deviceSessionCtx.setSessionInfo(SessionInfoCreator.create(msg, context, sessionId));
            
            log.info("[{}] Device info: {}", sessionId, msg.getDeviceInfo().getDeviceName());
            
            transportService.process(deviceSessionCtx.getSessionInfo(), SESSION_EVENT_MSG_OPEN, 
                    new TransportServiceCallback<Void>() {
                @Override
                public void onSuccess(Void msg) {
                    SessionMetaData sessionMetaData = transportService.registerAsyncSession(
                            deviceSessionCtx.getSessionInfo(), TcpTransportHandler.this);
                    
                    sendResponse(ctx, "AUTH_OK\n");
                    authenticated = true;
                    deviceSessionCtx.setConnected(true);
                    deviceSessionCtx.setChannel(ctx);
                    log.info("[{}] Client authenticated successfully! Device: {}", 
                            sessionId, deviceSessionCtx.getDeviceInfo().getDeviceName());
                }

                @Override
                public void onError(Throwable e) {
                    log.warn("[{}] Failed to open session", sessionId, e);
                    sendResponse(ctx, "AUTH_FAILED:Session error\n");
                    ctx.close();
                }
            });
        }
    }

    private void processDataMessage(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        try {
            String message = byteBuf.toString(StandardCharsets.UTF_8).trim();
            log.info("[{}] Processing data message: {}", sessionId, message);
            
            // Parse message type - expecting format: "TELEMETRY:data" or "ATTRIBUTES:data"
            int separatorIndex = message.indexOf(':');
            if (separatorIndex <= 0) {
                log.warn("[{}] Invalid message format: {}", sessionId, message);
                sendResponse(ctx, "INVALID_FORMAT\n");
                return;
            }
            
            String messageType = message.substring(0, separatorIndex);
            String data = message.substring(separatorIndex + 1);
            log.info("[{}] Message type: {}, data: {}", sessionId, messageType, data);
            
            ByteBuf dataBuf = ctx.alloc().buffer();
            dataBuf.writeBytes(data.getBytes(StandardCharsets.UTF_8));
            
            try {
                TcpTransportAdaptor adaptor = deviceSessionCtx.getPayloadAdaptor();
                log.info("[{}] Using adaptor: {}", sessionId, adaptor.getClass().getSimpleName());
                
                switch (messageType) {
                    case "TELEMETRY":
                        log.info("[{}] Converting telemetry...", sessionId);
                        TransportProtos.PostTelemetryMsg telemetryMsg = adaptor.convertToPostTelemetry(deviceSessionCtx, dataBuf);
                        log.info("[{}] Sending telemetry to transport service...", sessionId);
                        transportService.process(deviceSessionCtx.getSessionInfo(), telemetryMsg, null, 
                                getCallback(ctx, "TELEMETRY_OK\n", "TELEMETRY_FAILED\n"));
                        break;
                        
                    case "ATTRIBUTES":
                        log.info("[{}] Converting attributes...", sessionId);
                        TransportProtos.PostAttributeMsg attributeMsg = adaptor.convertToPostAttributes(deviceSessionCtx, dataBuf);
                        log.info("[{}] Sending attributes to transport service...", sessionId);
                        transportService.process(deviceSessionCtx.getSessionInfo(), attributeMsg, null,
                                getCallback(ctx, "ATTRIBUTES_OK\n", "ATTRIBUTES_FAILED\n"));
                        break;
                        
                    case "RPC_RESPONSE":
                        log.info("[{}] Converting RPC response...", sessionId);
                        TransportProtos.ToDeviceRpcResponseMsg rpcResponse = adaptor.convertToDeviceRpcResponse(deviceSessionCtx, dataBuf);
                        transportService.process(deviceSessionCtx.getSessionInfo(), rpcResponse,
                                getCallback(ctx, "RPC_RESPONSE_OK\n", "RPC_RESPONSE_FAILED\n"));
                        break;
                        
                    default:
                        log.warn("[{}] Unknown message type: {}", sessionId, messageType);
                        sendResponse(ctx, "UNKNOWN_MESSAGE_TYPE\n");
                }
            } finally {
                ReferenceCountUtil.release(dataBuf);
            }
            
        } catch (AdaptorException e) {
            log.error("[{}] Failed to process message", sessionId, e);
            sendResponse(ctx, "PROCESSING_FAILED:" + e.getMessage() + "\n");
        } catch (Exception e) {
            log.error("[{}] Unexpected error processing message", sessionId, e);
            sendResponse(ctx, "ERROR:" + e.getMessage() + "\n");
        }
    }

    /**
     * Send response message
     */
    private void sendResponse(ChannelHandlerContext ctx, String message) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        
        if (useBinaryProtocol) {
            // 使用Eelink厂商协议编码
            ByteBuf frame = org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrameEncoder.encodeResponse(
                    ctx.alloc(), messageBytes);
            ctx.writeAndFlush(frame);
            log.debug("[{}] Sent Eelink binary response: {} ({} bytes)", sessionId, message.trim(), frame.readableBytes());
        } else {
            // Send as plain text
            ctx.writeAndFlush(ctx.alloc().buffer().writeBytes(messageBytes));
        }
    }
    
    /**
     * Send message with payload (for server push messages)
     */
    private void sendMessageWithPayload(String prefix, ByteBuf payload) {
        try {
            byte[] payloadBytes = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), payloadBytes);
            
            if (useBinaryProtocol) {
                // 对于Eelink二进制协议，将前缀和数据组合后编码
                String combined = prefix + ":" + new String(payloadBytes, StandardCharsets.UTF_8);
                ByteBuf frame = org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrameEncoder.encodeResponse(
                        deviceSessionCtx.getChannel().alloc(), combined.getBytes(StandardCharsets.UTF_8));
                deviceSessionCtx.getChannel().writeAndFlush(frame);
            } else {
                // For string protocol, combine prefix and payload
                deviceSessionCtx.getChannel().writeAndFlush(
                        deviceSessionCtx.getChannel().alloc().buffer()
                                .writeBytes(prefix.getBytes())
                                .writeBytes(":".getBytes())
                                .writeBytes(payloadBytes)
                                .writeBytes("\n".getBytes()));
            }
        } catch (Exception e) {
            log.error("[{}] Failed to send message with payload", sessionId, e);
        }
    }

    private <T> TransportServiceCallback<Void> getCallback(ChannelHandlerContext ctx, 
                                                           String successMsg, 
                                                           String errorMsg) {
        return new TransportServiceCallback<>() {
            @Override
            public void onSuccess(Void msg) {
                sendResponse(ctx, successMsg);
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to process message", sessionId, e);
                sendResponse(ctx, errorMsg);
            }
        };
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            log.debug("[{}] IOException: {}", sessionId, cause.getMessage());
        } else {
            log.error("[{}] Unexpected Exception", sessionId, cause);
        }
        ctx.close();
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        log.trace("[{}] Channel closed!", sessionId);
        doDisconnect();
    }

    private void doDisconnect() {
        if (deviceSessionCtx.isConnected()) {
            log.debug("[{}] Client disconnected!", sessionId);
            transportService.process(deviceSessionCtx.getSessionInfo(), SESSION_EVENT_MSG_CLOSED, null);
            transportService.deregisterSession(deviceSessionCtx.getSessionInfo());
            deviceSessionCtx.setDisconnected();
        }
    }

    @Override
    public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg response) {
        log.trace("[{}] Received get attributes response", sessionId);
        try {
            TcpTransportAdaptor adaptor = deviceSessionCtx.getPayloadAdaptor();
            adaptor.convertToPublish(deviceSessionCtx, response)
                    .ifPresent(byteBuf -> {
                        sendMessageWithPayload("ATTRIBUTES_RESPONSE", byteBuf);
                        ReferenceCountUtil.release(byteBuf);
                    });
        } catch (Exception e) {
            log.trace("[{}] Failed to convert attributes response", sessionId, e);
        }
    }

    @Override
    public void onAttributeUpdate(UUID sessionId, TransportProtos.AttributeUpdateNotificationMsg notification) {
        log.trace("[{}] Received attributes update notification", sessionId);
        try {
            TcpTransportAdaptor adaptor = deviceSessionCtx.getPayloadAdaptor();
            adaptor.convertToPublish(deviceSessionCtx, notification)
                    .ifPresent(byteBuf -> {
                        sendMessageWithPayload("ATTRIBUTES_UPDATE", byteBuf);
                        ReferenceCountUtil.release(byteBuf);
                    });
        } catch (Exception e) {
            log.trace("[{}] Failed to convert attributes update", sessionId, e);
        }
    }

    @Override
    public void onRemoteSessionCloseCommand(UUID sessionId, TransportProtos.SessionCloseNotificationProto notification) {
        log.trace("[{}] Received remote session close command", sessionId);
        transportService.deregisterSession(deviceSessionCtx.getSessionInfo());
        deviceSessionCtx.getChannel().close();
    }

    @Override
    public void onToDeviceRpcRequest(UUID sessionId, TransportProtos.ToDeviceRpcRequestMsg rpcRequest) {
        log.trace("[{}] Received RPC request", sessionId);
        try {
            TcpTransportAdaptor adaptor = deviceSessionCtx.getPayloadAdaptor();
            adaptor.convertToPublish(deviceSessionCtx, rpcRequest)
                    .ifPresent(byteBuf -> {
                        sendMessageWithPayload("RPC_REQUEST", byteBuf);
                        ReferenceCountUtil.release(byteBuf);
                    });
        } catch (Exception e) {
            log.trace("[{}] Failed to convert RPC request", sessionId, e);
        }
    }

    @Override
    public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg rpcResponse) {
        log.trace("[{}] Received server RPC response", sessionId);
    }

    @Override
    public void onDeviceProfileUpdate(TransportProtos.SessionInfoProto sessionInfo, DeviceProfile deviceProfile) {
        log.trace("[{}] Device profile updated", sessionId);
        deviceSessionCtx.setDeviceProfile(deviceProfile);
    }

    @Override
    public void onDeviceUpdate(TransportProtos.SessionInfoProto sessionInfo, Device device, Optional<DeviceProfile> deviceProfileOpt) {
        log.trace("[{}] Device updated", sessionId);
    }

    @Override
    public void onDeviceDeleted(DeviceId deviceId) {
        log.trace("[{}] Device deleted", sessionId);
        deviceSessionCtx.getChannel().close();
    }

}

