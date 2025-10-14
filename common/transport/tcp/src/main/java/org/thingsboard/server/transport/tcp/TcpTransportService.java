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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ResourceLeakDetector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.TbTransportService;

/**
 * TCP Transport Service
 */
@Service("TcpTransportService")
@TbTcpTransportComponent
@Slf4j
public class TcpTransportService implements TbTransportService {

    @Value("${transport.tcp.bind_address:0.0.0.0}")
    private String host;
    
    @Value("${transport.tcp.bind_port:8883}")
    private Integer port;

    @Value("${transport.tcp.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${transport.tcp.ssl.bind_address:0.0.0.0}")
    private String sslHost;
    
    @Value("${transport.tcp.ssl.bind_port:8884}")
    private Integer sslPort;

    @Autowired
    private TcpTransportContext context;

    private Channel serverChannel;
    private Channel sslServerChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @PostConstruct
    public void init() throws Exception {
        log.info("Setting resource leak detector level to {}", context.getLeakDetectorLevel());
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.valueOf(context.getLeakDetectorLevel().toUpperCase()));

        log.info("Starting TCP transport...");
        log.info("Bind address: {}:{}", host, port);
        
        bossGroup = new NioEventLoopGroup(context.getBossGroupThreadCount());
        workerGroup = new NioEventLoopGroup(context.getWorkerGroupThreadCount());
        
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new TcpTransportServerInitializer(context, false))
                .childOption(ChannelOption.SO_KEEPALIVE, context.isKeepAlive());

        serverChannel = b.bind(host, port).sync().channel();
        log.info("TCP transport started on {}:{}!", host, port);
        
        if (sslEnabled) {
            log.info("Starting TCP SSL transport on {}:{}...", sslHost, sslPort);
            b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new TcpTransportServerInitializer(context, true))
                    .childOption(ChannelOption.SO_KEEPALIVE, context.isKeepAlive());
            sslServerChannel = b.bind(sslHost, sslPort).sync().channel();
            log.info("TCP SSL transport started on {}:{}!", sslHost, sslPort);
        }
        
        log.info("TCP transport started successfully!");
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Stopping TCP transport!");
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            if (sslEnabled && sslServerChannel != null) {
                sslServerChannel.close().sync();
            }
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
        }
        log.info("TCP transport stopped!");
    }

    @Override
    public String getName() {
        return "TCP";
    }
}

