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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
import org.thingsboard.server.transport.tcp.session.TcpDeviceSessionContext;

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
        
        log.info("[{}] Processing Eelink login request from device", sessionId);
        
        try {
            // 解析登陆请求（直接从frame的data字段获取）
            EelinkLoginRequest request = EelinkLoginRequest.parse(frame.getData());
            
            // 使用地址作为设备标识进行认证
            String deviceToken = frame.getAddressString();// request.getImeiString();
            log.info("[{}] Device Address: {}, Software: {}, Hardware: {}, ResetCount: {}", 
                    sessionId,
                    deviceToken,
                    request.getSoftwareVersion(),
                    request.getHardwareVersion(),
                    request.getResetCount() & 0xFF);
            
            // 构建认证请求
            TransportProtos.ValidateBasicMqttCredRequestMsg.Builder authRequest = 
                    TransportProtos.ValidateBasicMqttCredRequestMsg.newBuilder()
                    .setClientId(sessionId.toString())
                    .setUserName(deviceToken);  // 使用地址作为用户名
            
            TransportService transportService = context.getTransportService();
            
            // 发送认证请求
            transportService.process(DeviceTransportType.DEFAULT, authRequest.build(),
                    new TransportServiceCallback<>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
                            if (!msg.hasDeviceInfo()) {
                                // 认证失败
                                log.warn("[{}] Device authentication failed for Address: {}", sessionId, deviceToken);
                                EelinkLoginResponse response = EelinkLoginResponse.error(
                                        frame.getAddress(), 
                                        EelinkLoginResponse.ERROR_NOT_CONFIGURED);
                                ctx.writeAndFlush(response.encode(ctx.alloc()));
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
                                                null);  // 暂时不传SessionMsgListener
                                        
                                        // 发送成功响应
                                        EelinkLoginResponse response = EelinkLoginResponse.success(
                                                frame.getAddress());
                                        ctx.writeAndFlush(response.encode(ctx.alloc()));
                                        
                                        // 标记已连接
                                        deviceSessionCtx.setConnected(true);
                                        deviceSessionCtx.setChannel(ctx);
                                        
                                        log.info("[{}] Eelink device login successful: {}", 
                                                sessionId, msg.getDeviceInfo().getDeviceName());
                                    }
                                    
                                    @Override
                                    public void onError(Throwable e) {
                                        log.error("[{}] Failed to open session", sessionId, e);
                                        EelinkLoginResponse response = EelinkLoginResponse.error(
                                                frame.getAddress(), 
                                                EelinkLoginResponse.ERROR_DATA_INVALID);
                                        ctx.writeAndFlush(response.encode(ctx.alloc()));
                                        ctx.close();
                                    }
                                });
                            }
                        }
                        
                        @Override
                        public void onError(Throwable e) {
                            log.error("[{}] Authentication service error", sessionId, e);
                            EelinkLoginResponse response = EelinkLoginResponse.error(
                                    frame.getAddress(), 
                                    EelinkLoginResponse.ERROR_DATA_INVALID);
                            ctx.writeAndFlush(response.encode(ctx.alloc()));
                            ctx.close();
                        }
                    });
            
        } catch (Exception e) {
            log.error("[{}] Failed to process login request", sessionId, e);
            EelinkLoginResponse response = EelinkLoginResponse.error(
                    frame.getAddress(), 
                    EelinkLoginResponse.ERROR_DATA_INVALID);
            ctx.writeAndFlush(response.encode(ctx.alloc()));
            ctx.close();
        }
    }
}

