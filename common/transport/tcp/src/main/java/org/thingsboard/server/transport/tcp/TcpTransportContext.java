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

import io.netty.handler.ssl.SslHandler;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.transport.TransportContext;
import org.thingsboard.server.transport.tcp.adaptors.BinaryTcpAdaptor;
import org.thingsboard.server.transport.tcp.adaptors.StringTcpAdaptor;
import org.thingsboard.server.transport.tcp.adaptors.TcpTransportAdaptor;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP Transport Context
 */
@Slf4j
@Component
@TbTcpTransportComponent
public class TcpTransportContext extends TransportContext {

    @Getter
    @Autowired
    private StringTcpAdaptor stringTcpAdaptor;

    @Getter
    @Autowired
    private BinaryTcpAdaptor binaryTcpAdaptor;

    @Getter
    @Autowired(required = false)
    private org.thingsboard.server.transport.tcp.protocol.eelink.EelinkMessageHandler eelinkMessageHandler;

    @Getter
    @Autowired(required = false)
    private org.thingsboard.server.transport.tcp.protocol.eelink.EelinkRpcCommandMapper eelinkRpcCommandMapper;

    @Getter
    @Value("${transport.tcp.netty.max_payload_size:65536}")
    private Integer maxPayloadSize;

    @Getter
    @Setter
    private SslHandler sslHandler;

    @Getter
    @Value("${transport.tcp.msg_queue_size_per_device_limit:100}")
    private int messageQueueSizePerDeviceLimit;

    @Getter
    @Value("${transport.tcp.timeout:10000}")
    private long timeout;

    @Getter
    @Value("${transport.tcp.disconnect_timeout:1000}")
    private long disconnectTimeout;

    @Getter
    @Value("${transport.tcp.netty.leak_detector_level:DISABLED}")
    private String leakDetectorLevel;

    @Getter
    @Value("${transport.tcp.netty.boss_group_thread_count:1}")
    private Integer bossGroupThreadCount;

    @Getter
    @Value("${transport.tcp.netty.worker_group_thread_count:12}")
    private Integer workerGroupThreadCount;

    @Getter
    @Value("${transport.tcp.netty.so_keep_alive:true}")
    private boolean keepAlive;

    @Getter
    @Value("${transport.tcp.protocol_type:string}")
    private String protocolType;

    private TcpTransportAdaptor activeAdaptor;

    private final AtomicInteger connectionsCounter = new AtomicInteger();

    @PostConstruct
    public void init() {
        super.init();
        transportService.createGaugeStats("tcpOpenConnections", connectionsCounter);
        
        // Initialize the active adaptor based on configuration
        if ("binary".equalsIgnoreCase(protocolType) || "protobuf".equalsIgnoreCase(protocolType)) {
            activeAdaptor = binaryTcpAdaptor;
            log.info("TCP Transport using BINARY/Protobuf adaptor");
        } else {
            activeAdaptor = stringTcpAdaptor;
            log.info("TCP Transport using STRING/JSON adaptor");
        }
    }
    
    public TcpTransportAdaptor getActiveAdaptor() {
        return activeAdaptor;
    }

    public void channelRegistered() {
        connectionsCounter.incrementAndGet();
    }

    public void channelUnregistered() {
        connectionsCounter.decrementAndGet();
    }

    public boolean checkAddress(InetSocketAddress address) {
        return rateLimitService.checkAddress(address);
    }

    public void onAuthSuccess(InetSocketAddress address) {
        rateLimitService.onAuthSuccess(address);
    }

    public void onAuthFailure(InetSocketAddress address) {
        rateLimitService.onAuthFailure(address);
    }

}

