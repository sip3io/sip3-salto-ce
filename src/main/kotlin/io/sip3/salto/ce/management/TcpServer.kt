/*
 * Copyright 2018-2026 SIP3.IO, Corp.
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

package io.sip3.salto.ce.management

import io.netty.buffer.ByteBufUtil
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.parsetools.RecordParser
import mu.KotlinLogging
import java.net.URI

@Instance(singleton = true)
@ConditionalOnProperty(pointer = "/management", matcher = ".*: ?\"?tcp://.*")
open class TcpServer : AbstractServer() {

    private val logger = KotlinLogging.logger {}

    private lateinit var uri: URI
    private var bufferSize: Int? = null
    private var delimiter = "\r\n\r\n3PIS\r\n\r\n"

    private val sockets = mutableMapOf<URI, NetSocket>()

    override fun readConfig() {
        config().getJsonObject("management")?.let { management ->
            val config = management.getJsonObject("tcp") ?: management
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))
            bufferSize = config.getInteger("buffer_size")
            config.getString("delimiter")?.let { delimiter = it }
        }
    }

    override fun startServer() {
        val options = tcpConnectionOptions()
        vertx.createNetServer(options)
            .connectHandler { socket ->
                val sender =  socket.remoteAddress()
                val senderUri = URI("tcp://${sender.host()}:${sender.port()}")
                logger.debug { "TCP connection established from $sender" }
                sockets[senderUri] = socket

                val parser = RecordParser.newDelimited(delimiter) { buffer ->
                    try {
                        handle(senderUri, buffer.toJsonObject())
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
                socket.closeHandler {
                    sockets.remove(senderUri)
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
        return  NetServerOptions().apply {
            bufferSize?.let { receiveBufferSize = it }
        }
    }

    override fun send(message: JsonObject, uris: List<URI>) {
        val buffer = message.toBuffer()
        uris.filter { it.scheme == "tcp" }
            .mapNotNull { sockets[it] }
            .forEach { socket ->
                socket.write(buffer.appendString(delimiter))
            }
    }
}