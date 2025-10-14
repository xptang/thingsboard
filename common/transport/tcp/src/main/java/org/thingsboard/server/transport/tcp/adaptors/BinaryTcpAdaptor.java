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
package org.thingsboard.server.transport.tcp.adaptors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.adaptor.AdaptorException;
import org.thingsboard.server.common.adaptor.JsonConverter;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.AttributeUpdateNotificationMsg;
import org.thingsboard.server.gen.transport.TransportProtos.GetAttributeResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostAttributeMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostTelemetryMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToDeviceRpcRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToDeviceRpcResponseMsg;
import org.thingsboard.server.transport.tcp.TbTcpTransportComponent;
import org.thingsboard.server.transport.tcp.session.TcpDeviceSessionContext;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Binary/Protobuf TCP Protocol Adaptor
 */
@Component
@TbTcpTransportComponent
@Slf4j
public class BinaryTcpAdaptor implements TcpTransportAdaptor {

    @Override
    public PostTelemetryMsg convertToPostTelemetry(TcpDeviceSessionContext ctx, ByteBuf inbound) throws AdaptorException {
        String payload = inbound.toString(StandardCharsets.UTF_8);
        try {
            return JsonConverter.convertToTelemetryProto(JsonParser.parseString(payload));
        } catch (IllegalStateException | JsonSyntaxException e) {
            throw new AdaptorException("Failed to decode telemetry: " + e.getMessage(), e);
        }
    }

    @Override
    public PostAttributeMsg convertToPostAttributes(TcpDeviceSessionContext ctx, ByteBuf inbound) throws AdaptorException {
        String payload = inbound.toString(StandardCharsets.UTF_8);
        try {
            return JsonConverter.convertToAttributesProto(JsonParser.parseString(payload));
        } catch (IllegalStateException | JsonSyntaxException e) {
            throw new AdaptorException("Failed to decode attributes: " + e.getMessage(), e);
        }
    }

    @Override
    public ToDeviceRpcResponseMsg convertToDeviceRpcResponse(TcpDeviceSessionContext ctx, ByteBuf inbound) throws AdaptorException {
        String payload = inbound.toString(StandardCharsets.UTF_8);
        try {
            JsonObject jsonObject = JsonParser.parseString(payload).getAsJsonObject();
            return TransportProtos.ToDeviceRpcResponseMsg.newBuilder()
                    .setRequestId(jsonObject.get("id").getAsInt())
                    .setPayload(jsonObject.get("data").toString())
                    .build();
        } catch (Exception e) {
            throw new AdaptorException("Failed to decode RPC response: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ByteBuf> convertToPublish(TcpDeviceSessionContext ctx, GetAttributeResponseMsg responseMsg) throws AdaptorException {
        try {
            JsonElement response = JsonConverter.toJson(responseMsg);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            return Optional.of(Unpooled.wrappedBuffer(bytes));
        } catch (Exception e) {
            log.warn("Failed to convert GetAttributeResponseMsg to ByteBuf", e);
            throw new AdaptorException(e);
        }
    }

    @Override
    public Optional<ByteBuf> convertToPublish(TcpDeviceSessionContext ctx, AttributeUpdateNotificationMsg notificationMsg) throws AdaptorException {
        try {
            JsonElement notification = JsonConverter.toJson(notificationMsg);
            byte[] bytes = notification.toString().getBytes(StandardCharsets.UTF_8);
            return Optional.of(Unpooled.wrappedBuffer(bytes));
        } catch (Exception e) {
            log.warn("Failed to convert AttributeUpdateNotificationMsg to ByteBuf", e);
            throw new AdaptorException(e);
        }
    }

    @Override
    public Optional<ByteBuf> convertToPublish(TcpDeviceSessionContext ctx, ToDeviceRpcRequestMsg rpcRequest) throws AdaptorException {
        try {
            JsonElement rpcRequestNode = JsonConverter.toJson(rpcRequest, false);
            byte[] bytes = rpcRequestNode.toString().getBytes(StandardCharsets.UTF_8);
            return Optional.of(Unpooled.wrappedBuffer(bytes));
        } catch (Exception e) {
            log.warn("Failed to convert ToDeviceRpcRequestMsg to ByteBuf", e);
            throw new AdaptorException(e);
        }
    }

}

