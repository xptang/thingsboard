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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * TCP Transport Server Initializer
 */
@Slf4j
public class TcpTransportServerInitializer extends ChannelInitializer<SocketChannel> {

    private final TcpTransportContext context;
    private final boolean sslEnabled;
    private final boolean useBinaryProtocol;

    public TcpTransportServerInitializer(TcpTransportContext context, boolean sslEnabled) {
        this.context = context;
        this.sslEnabled = sslEnabled;
        this.useBinaryProtocol = "binary".equalsIgnoreCase(context.getProtocolType()) || 
                                 "protobuf".equalsIgnoreCase(context.getProtocolType());
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        SslHandler sslHandler = null;
        
        if (sslEnabled && context.getSslHandler() != null) {
            sslHandler = context.getSslHandler();
            pipeline.addLast(sslHandler);
        }
        
        // Add idle state handler - disconnect if no data received for 5 minutes
        pipeline.addLast("idleStateHandler", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
        
        // Add frame decoder based on protocol type
        if (useBinaryProtocol) {
            log.info("Using EelinkFrameDecoder (Eelink Vendor Protocol - 优联时空)");
            // 使用Eelink厂商协议解码器
            pipeline.addLast("frameDecoder", new org.thingsboard.server.transport.tcp.protocol.eelink.EelinkFrameDecoder());
        } else {
            log.info("Using LineBasedFrameDecoder (String Protocol)");
            // Add line-based frame decoder (splits on \n) for string protocol
            pipeline.addLast("frameDecoder", new LineBasedFrameDecoder(context.getMaxPayloadSize()));
        }

        TcpTransportHandler handler = new TcpTransportHandler(context, sslHandler, useBinaryProtocol);
        pipeline.addLast(handler);
        ch.closeFuture().addListener(handler);
    }

}

