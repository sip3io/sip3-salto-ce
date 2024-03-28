/*
 * Copyright 2018-2024 SIP3.IO, Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.salto.ce.server

import io.netty.buffer.ByteBufUtil
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.JksOptions
import io.vertx.core.net.NetServerOptions
import io.vertx.core.parsetools.RecordParser
import mu.KotlinLogging
import java.net.URI

/**
 * Retrieves SIP3 and HEP3 packets
 */
@Instance
@ConditionalOnProperty("/server/tcp")
open class TcpServer : Server("tcp") {

    private val logger = KotlinLogging.logger {}

    override fun startServer() {
        val options = NetServerOptions().apply {
            bufferSize?.let { receiveBufferSize = it }
            sslConfig?.let { config ->
                isSsl = true
                keyCertOptions = JksOptions().apply {
                    path = config.getString("key_store")
                    password = config.getString("key_store_password")
                }
            }
        }

        vertx.createNetServer(options)
            .connectHandler { socket ->
                val sender = Address().apply {
                    addr = socket.remoteAddress().host()
                    port = socket.remoteAddress().port()
                }
                logger.debug { "TCP connection established from $sender" }

                val parser = RecordParser.newDelimited(delimiter) { buffer ->
                    try {
                        onRawPacket(sender, buffer)
                    } catch (e: Exception) {
                        logger.error(e) { "TcpServer 'onRawPacket()' failed." }
                        logger.debug { "Sender: $sender, buffer: ${ByteBufUtil.prettyHexDump(buffer.byteBuf)}" }
                    }
                }

                socket.handler { buffer ->
                    try {
                        parser.handle(buffer)
                    } catch (e: Exception) {
                        logger.error(e) { "RecordParser 'handle()' failed." }
                        logger.debug { "Sender: $sender, buffer: ${ByteBufUtil.prettyHexDump(buffer.byteBuf)}" }
                    }
                }
            }
            .listen(uri.port, uri.host)
            .onFailure { t ->
                logger.error(t) { "TCP connection failed. URI: $uri" }
                throw t
            }
            .onSuccess { logger.info { "Listening on $uri" } }
    }
}