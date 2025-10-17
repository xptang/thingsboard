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

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.rpc.RpcStatus;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.tcp.session.TcpDeviceSessionContext;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkCommandRequest;
import org.thingsboard.server.transport.tcp.protocol.eelink.messages.EelinkCommandResponse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eelink协议会话消息监听器
 * 接收来自ThingsBoard核心的下行消息
 */
@Slf4j
public class EelinkSessionMsgListener implements SessionMsgListener {
    
    private final TcpDeviceSessionContext deviceSessionCtx;
    private final TransportService transportService;
    private final EelinkRpcCommandMapper commandMapper;
    
    // 存储待响应的RPC请求（requestId → RPC消息）
    private final ConcurrentHashMap<Integer, TransportProtos.ToDeviceRpcRequestMsg> pendingRpcRequests 
            = new ConcurrentHashMap<>();
    
    public EelinkSessionMsgListener(TcpDeviceSessionContext deviceSessionCtx, 
                                    TransportService transportService,
                                    EelinkRpcCommandMapper commandMapper) {
        this.deviceSessionCtx = deviceSessionCtx;
        this.transportService = transportService;
        this.commandMapper = commandMapper;
        
        UUID sessionId = new UUID(
                deviceSessionCtx.getSessionInfo().getSessionIdMSB(),
                deviceSessionCtx.getSessionInfo().getSessionIdLSB());
        
        log.info("[{}] 🎯 EelinkSessionMsgListener created successfully", sessionId);
        log.info("[{}]   - Device: {}", sessionId, 
                deviceSessionCtx.getDeviceInfo() != null ? deviceSessionCtx.getDeviceInfo().getDeviceName() : "N/A");
        log.info("[{}]   - CommandMapper: {}", sessionId, 
                commandMapper != null ? "Available" : "NULL");
        log.info("[{}]   - This listener will receive RPC requests from ThingsBoard", sessionId);
    }
    
    /**
     * 接收来自服务器的RPC请求
     */
    @Override
    public void onToDeviceRpcRequest(UUID sessionId, TransportProtos.ToDeviceRpcRequestMsg rpcRequest) {
        log.info("========================================");
        log.info("[{}] ⭐ Received RPC request from server:", sessionId);
        log.info("[{}]   - Method: {}", sessionId, rpcRequest.getMethodName());
        log.info("[{}]   - Params: {}", sessionId, rpcRequest.getParams());
        log.info("[{}]   - RequestId: {}", sessionId, rpcRequest.getRequestId());
        log.info("[{}]   - Persisted: {}", sessionId, rpcRequest.getPersisted());
        log.info("[{}]   - Oneway: {}", sessionId, rpcRequest.getOneway());
        log.info("[{}]   - DeviceId: {}", sessionId, deviceSessionCtx.getDeviceInfo() != null 
                ? deviceSessionCtx.getDeviceInfo().getDeviceName() 
                : "N/A");
        log.info("========================================");
        
        try {
            ChannelHandlerContext ctx = deviceSessionCtx.getChannel();
            if (ctx == null || !ctx.channel().isActive()) {
                log.error("[{}] Channel is not active, cannot send RPC request", sessionId);
                sendRpcError(rpcRequest, "Channel not active");
                return;
            }
            
            // 1. 使用映射器创建Eelink命令请求
            EelinkCommandRequest eelinkRequest;
            try {
                eelinkRequest = commandMapper.createRequest(
                        rpcRequest.getMethodName(),
                        rpcRequest.getParams(),
                        rpcRequest.getRequestId());
            } catch (EelinkRpcCommandMapper.UnsupportedCommandException e) {
                log.warn("[{}] Unsupported RPC method: {}", sessionId, rpcRequest.getMethodName());
                sendRpcError(rpcRequest, e.getMessage());
                return;
            }
            
            // 2. 存储RPC请求，等待设备响应
            pendingRpcRequests.put(rpcRequest.getRequestId(), rpcRequest);
            log.info("[{}] Stored pending RPC request: requestId={}", sessionId, rpcRequest.getRequestId());
            
            // 3. 获取设备地址并转换为二进制格式
            String deviceAddressStr = deviceSessionCtx.getAuthenticatedDeviceAddress();
            if (deviceAddressStr == null) {
                log.error("[{}] Device address is null", sessionId);
                pendingRpcRequests.remove(rpcRequest.getRequestId());
                sendRpcError(rpcRequest, "Device address not found");
                return;
            }
            
            // ⭐ 将16进制字符串转换为字节数组（如"93640000" → [0x93, 0x64, 0x00, 0x00]）
            byte[] deviceAddress = org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig.hexToBytes(deviceAddressStr);
            log.debug("[{}] Device address converted: {} → {} bytes", 
                     sessionId, deviceAddressStr, deviceAddress.length);
            
            // 4. 编码为Eelink协议帧
            ByteBuf commandFrame = eelinkRequest.encodeToFrame(ctx.alloc(), deviceAddress);
            
            // 5. 发送到设备
            ctx.writeAndFlush(commandFrame).addListener(future -> {
                if (future.isSuccess()) {
                    log.info("[{}] ✅ RPC command sent successfully: {} → frameCode=0x{}", 
                            sessionId,
                            rpcRequest.getMethodName(),
                            String.format("%02X", eelinkRequest.getFrameCode() & 0xFF));
                    
                    // 更新RPC状态为SENT
                    if (rpcRequest.getPersisted()) {
                        transportService.process(deviceSessionCtx.getSessionInfo(),
                                rpcRequest, RpcStatus.SENT, TransportServiceCallback.EMPTY);
                    } else if (rpcRequest.getOneway()) {
                        // 单向RPC，立即标记为DELIVERED，并移除待处理队列
                        log.info("[{}] Oneway RPC, marking as DELIVERED immediately", sessionId);
                        pendingRpcRequests.remove(rpcRequest.getRequestId());
                        transportService.process(deviceSessionCtx.getSessionInfo(),
                                rpcRequest, RpcStatus.DELIVERED, TransportServiceCallback.EMPTY);
                    }
                } else {
                    log.error("[{}] ❌ Failed to send RPC command", sessionId, future.cause());
                    pendingRpcRequests.remove(rpcRequest.getRequestId());
                    sendRpcError(rpcRequest, "Send failed: " + future.cause().getMessage());
                }
            });
            
        } catch (Exception e) {
            log.error("[{}] Failed to process RPC request", sessionId, e);
            pendingRpcRequests.remove(rpcRequest.getRequestId());
            sendRpcError(rpcRequest, "Processing error: " + e.getMessage());
        }
    }
    
    /**
     * ⭐ 处理Eelink命令响应（由EelinkMessageHandler调用）
     */
    public void handleCommandResponse(EelinkCommandResponse eelinkResponse) {
        UUID sessionId = new UUID(
                deviceSessionCtx.getSessionInfo().getSessionIdMSB(),
                deviceSessionCtx.getSessionInfo().getSessionIdLSB());
        
        int requestId = eelinkResponse.getRequestId();
        
        log.info("========================================");
        log.info("[{}] ⭐ Received command response from device:", sessionId);
        log.info("[{}]   - FrameCode: 0x{}", sessionId, String.format("%02X", eelinkResponse.getFrameCode() & 0xFF));
        log.info("[{}]   - RequestId: {}", sessionId, requestId);
        log.info("[{}]   - Success: {}", sessionId, eelinkResponse.isSuccess());
        log.info("========================================");
        
        // 1. 查找对应的RPC请求
        TransportProtos.ToDeviceRpcRequestMsg rpcRequest = pendingRpcRequests.remove(requestId);
        if (rpcRequest == null) {
            log.warn("[{}] Received response for unknown requestId: {}", sessionId, requestId);
            return;
        }
        
        log.info("[{}] Found pending RPC request: method={}", sessionId, rpcRequest.getMethodName());
        
        // 2. 转换为ThingsBoard RPC响应
        JsonObject responseData = eelinkResponse.toRpcResponse();
        
        log.info("[{}] Translated response: {}", sessionId, responseData);
        
        TransportProtos.ToDeviceRpcResponseMsg.Builder builder = 
                TransportProtos.ToDeviceRpcResponseMsg.newBuilder()
                        .setRequestId(requestId);
        
        if (eelinkResponse.isSuccess()) {
            builder.setPayload(responseData.toString());
        } else {
            String error = responseData.has("error") 
                    ? responseData.get("error").getAsString() 
                    : "Command execution failed";
            builder.setError(error);
        }
        
        TransportProtos.ToDeviceRpcResponseMsg rpcResponse = builder.build();
        
        // 3. 发送响应到ThingsBoard
        transportService.process(deviceSessionCtx.getSessionInfo(),
                rpcResponse, TransportServiceCallback.EMPTY);
        
        // 4. 更新RPC状态为DELIVERED
        transportService.process(deviceSessionCtx.getSessionInfo(),
                rpcRequest, RpcStatus.DELIVERED, true, TransportServiceCallback.EMPTY);
        
        log.info("[{}] ✅ RPC response sent to ThingsBoard successfully", sessionId);
    }
    
    /**
     * 发送RPC错误响应
     */
    private void sendRpcError(TransportProtos.ToDeviceRpcRequestMsg rpcRequest, String errorMsg) {
        log.error("Sending RPC error response: requestId={}, error={}", rpcRequest.getRequestId(), errorMsg);
        
        TransportProtos.ToDeviceRpcResponseMsg errorResponse = 
                TransportProtos.ToDeviceRpcResponseMsg.newBuilder()
                        .setRequestId(rpcRequest.getRequestId())
                        .setError(errorMsg)
                        .build();
        
        transportService.process(deviceSessionCtx.getSessionInfo(),
                errorResponse, TransportServiceCallback.EMPTY);
    }
    
    /**
     * 接收属性更新通知
     */
    @Override
    public void onAttributeUpdate(UUID sessionId, TransportProtos.AttributeUpdateNotificationMsg notification) {
        log.info("[{}] Received attribute update notification:", sessionId);
        log.info("[{}]   - Shared attributes count: {}", sessionId, notification.getSharedUpdatedCount());
        log.info("[{}]   - Deleted attributes count: {}", sessionId, notification.getSharedDeletedCount());
        
        // TODO: 后续实现属性更新推送
    }
    
    /**
     * 接收属性查询响应
     */
    @Override
    public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg responseMsg) {
        log.trace("[{}] Received get attributes response", deviceSessionCtx.getSessionId());
    }
    
    /**
     * 接收远程会话关闭命令
     */
    @Override
    public void onRemoteSessionCloseCommand(UUID sessionId, TransportProtos.SessionCloseNotificationProto notification) {
        log.info("[{}] Received remote session close command: {}", sessionId, notification.getMessage());
        log.info("[{}]   - Reason: {}", sessionId, notification.getReason());
        
        transportService.deregisterSession(deviceSessionCtx.getSessionInfo());
        
        if (deviceSessionCtx.getChannel() != null) {
            deviceSessionCtx.getChannel().close();
        }
    }
    
    /**
     * 接收服务器RPC响应（设备发起的RPC）
     */
    @Override
    public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg toServerResponse) {
        log.trace("[{}] Received server RPC response", deviceSessionCtx.getSessionId());
    }
    
    /**
     * 设备被删除
     */
    @Override
    public void onDeviceDeleted(DeviceId deviceId) {
        log.info("[{}] Device deleted: {}", deviceSessionCtx.getSessionId(), deviceId);
        
        if (deviceSessionCtx.getChannel() != null) {
            deviceSessionCtx.getChannel().close();
        }
    }
    
    /**
     * 设备配置更新
     */
    @Override
    public void onDeviceProfileUpdate(TransportProtos.SessionInfoProto sessionInfo, DeviceProfile deviceProfile) {
        log.debug("[{}] Device profile updated: {}", deviceSessionCtx.getSessionId(), deviceProfile.getName());
        deviceSessionCtx.setDeviceProfile(deviceProfile);
    }
    
    /**
     * 设备信息更新
     */
    @Override
    public void onDeviceUpdate(TransportProtos.SessionInfoProto sessionInfo, Device device, Optional<DeviceProfile> deviceProfileOpt) {
        log.debug("[{}] Device updated: {}", deviceSessionCtx.getSessionId(), device.getName());
        deviceProfileOpt.ifPresent(deviceSessionCtx::setDeviceProfile);
    }
    
    /**
     * 获取待处理的RPC请求Map（供EelinkMessageHandler使用）
     */
    public ConcurrentHashMap<Integer, TransportProtos.ToDeviceRpcRequestMsg> getPendingRpcRequests() {
        return pendingRpcRequests;
    }
}

