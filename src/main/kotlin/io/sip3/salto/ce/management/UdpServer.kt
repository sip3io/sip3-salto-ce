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

package io.sip3.salto.ce.management

import io.sip3.commons.util.toURI
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.net.URI

@Instance(singleton = true)
@ConditionalOnProperty(pointer = "/management", matcher = ".*: ?\"?udp://.*")
open class UdpServer : AbstractServer() {

    private val logger = KotlinLogging.logger {}

    private lateinit var uri: URI

    private lateinit var socket: DatagramSocket

    override fun readConfig() {
        config().getJsonObject("management")?.let { management ->
            val config = management.getJsonObject("udp") ?: management
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))
        }
    }

    override fun startServer() {
        val options = DatagramSocketOptions().apply {
            isIpV6 = uri.host.matches(Regex("\\[.*]"))
        }

        socket = vertx.createDatagramSocket(options)

        socket.handler { packet ->
            val socketAddress = packet.sender()
            val senderUri = socketAddress.toURI("udp", options.isIpV6)
            val buffer = packet.data()
            try {
                val message = buffer.toJsonObject()
                handle(senderUri, message)
            } catch (e: Exception) {
                logger.error(e) { "ManagementSocket 'handle()' failed." }
            }
        }

        socket.listen(uri.port, uri.host)
            .onFailure { t ->
                logger.error(t) { "UDP connection failed. URI: $uri" }
                throw t
            }
            .onSuccess {
                logger.info { "Listening on $uri" }
            }
    }

    override fun send(message: JsonObject, uris: List<URI>) {
        val buffer = message.toBuffer()
        uris.map { Pair(it.host, it.port) }
            .forEach { (host, port) ->
                logger.trace { "Sending message to $host:$port" }
                socket.send(buffer, port, host)
            }
    }
}