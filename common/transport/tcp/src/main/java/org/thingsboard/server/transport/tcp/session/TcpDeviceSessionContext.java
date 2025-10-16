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
package org.thingsboard.server.transport.tcp.session;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.TransportPayloadType;
import org.thingsboard.server.common.transport.auth.TransportDeviceInfo;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.tcp.TcpTransportContext;
import org.thingsboard.server.transport.tcp.adaptors.BinaryTcpAdaptor;
import org.thingsboard.server.transport.tcp.adaptors.StringTcpAdaptor;
import org.thingsboard.server.transport.tcp.adaptors.TcpTransportAdaptor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP Device Session Context
 */
@Data
@Slf4j
public class TcpDeviceSessionContext {

    private final UUID sessionId;
    private final TcpTransportContext context;

    private volatile ChannelHandlerContext channel;
    private volatile TransportDeviceInfo deviceInfo;
    private volatile DeviceProfile deviceProfile;
    private volatile TransportProtos.SessionInfoProto sessionInfo;
    private volatile boolean connected;
    private volatile String authenticatedDeviceAddress;  // 认证的设备地址（用于Eelink协议）

    private final AtomicInteger msgIdCounter = new AtomicInteger(0);

    public TcpDeviceSessionContext(UUID sessionId, TcpTransportContext context) {
        this.sessionId = sessionId;
        this.context = context;
    }

    public TcpTransportAdaptor getPayloadAdaptor() {
        return context.getActiveAdaptor();
    }

    public int nextMsgId() {
        return msgIdCounter.incrementAndGet();
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setDisconnected() {
        this.connected = false;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public ChannelHandlerContext getChannel() {
        return channel;
    }

    public void setChannel(ChannelHandlerContext channel) {
        this.channel = channel;
    }

}

