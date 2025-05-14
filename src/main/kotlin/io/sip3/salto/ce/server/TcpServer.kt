/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.domain.Address
import io.vertx.core.net.NetServerOptions
import io.vertx.core.parsetools.RecordParser
import mu.KotlinLogging
import java.net.URI

/**
 * Retrieves SIP3 and HEP3 packets via TCP
 */
@ConditionalOnProperty(pointer = "/server", matcher = ".*: ?\"?tcp://.*")
@Instance(worker = true)
open class TcpServer : AbstractServer() {

    private val logger = KotlinLogging.logger {}

    private lateinit var uri: URI
    private var bufferSize: Int? = null
    private var delimiter = "\r\n\r\n3PIS\r\n\r\n"

    override fun readConfig() {
        config().getJsonObject("server")?.let { server ->
            val config = server.getJsonObject("tcp") ?: server
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))
            bufferSize = config.getInteger("buffer_size")
            config.getString("delimiter")?.let { delimiter = it }
        }
    }

    override fun startServer() {
        val options = tcpConnectionOptions()
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
                        logger.error(e) { "Server 'onRawPacket()' failed." }
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

    open fun tcpConnectionOptions(): NetServerOptions {
        return NetServerOptions().apply {
            bufferSize?.let { receiveBufferSize = it }
        }
    }
}