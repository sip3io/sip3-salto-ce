/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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

import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.JksOptions
import io.vertx.core.net.NetServerOptions
import mu.KotlinLogging
import java.net.URI

/**
 * Retrieves SIP3 and HEP3 packets
 */
@Instance(singleton = true)
class Server : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PROTO_SIP3 = "SIP3"
        const val PROTO_HEP3 = "HEP3"
        val PROTO_HEP2 = byteArrayOf(0x02, 0x10, 0x02)
    }

    private lateinit var uri: URI
    private var bufferSize: Int? = null
    private var sslConfig: JsonObject? = null

    private val packetsReceived = Metrics.counter("packets_received")

    override fun start() {
        config().getJsonObject("server").let { config ->
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))
            bufferSize = config.getInteger("buffer-size")
            sslConfig = config.getJsonObject("ssl")
        }

        when (uri.scheme) {
            "udp" -> startUdpServer()
            "tcp" -> startTcpServer()
            else -> NotImplementedError("Unknown protocol: '${uri.scheme}'")
        }
    }

    private fun startUdpServer() {
        val options = DatagramSocketOptions().apply {
            bufferSize?.let { receiveBufferSize = it }
        }

        val socket = vertx.createDatagramSocket(options)
        socket.handler { packet ->
            val sender = Address().apply {
                addr = packet.sender().host()
                port = packet.sender().port()
            }
            val buffer = packet.data()
            onRawPacket(sender, buffer)
        }

        socket.listen(uri.port, uri.host) { connection ->
            if (connection.failed()) {
                logger.error("UDP connection failed. URI: $uri", connection.cause())
                throw connection.cause()
            }
            logger.info("Listening on $uri")
        }
    }

    private fun startTcpServer() {
        val options = NetServerOptions().apply {
            bufferSize?.let { receiveBufferSize = it }
            sslConfig?.let { config ->
                isSsl = true
                keyCertOptions = JksOptions().apply {
                    path = config.getString("key-store")
                    password = config.getString("key-store-password")
                }
            }
        }

        val server = vertx.createNetServer(options)
        server.connectHandler { socket ->
            val sender = Address().apply {
                addr = socket.remoteAddress().host()
                port = socket.remoteAddress().port()
            }
            socket.handler { buffer ->
                onRawPacket(sender, buffer)
            }
        }

        server.listen(uri.port, uri.host) { connection ->
            if (connection.failed()) {
                logger.error("TCP connection failed. URI: $uri", connection.cause())
                throw connection.cause()
            }
            logger.info("Listening on $uri")
        }
    }

    fun onRawPacket(sender: Address, buffer: Buffer) {
        packetsReceived.increment()

        if (buffer.length() >= 4) {
            // SIP3 and HEP3
            when (buffer.getString(0, 4)) {
                PROTO_SIP3 -> vertx.eventBus().localRequest<Any>(RoutesCE.sip3, Pair(sender, buffer))
                PROTO_HEP3 -> vertx.eventBus().localRequest<Any>(RoutesCE.hep3, Pair(sender, buffer))
            }

            // HEP2
            val prefix = buffer.getBytes(0, 3)
            if (prefix.contentEquals(PROTO_HEP2)) {
                vertx.eventBus().localRequest<Any>(RoutesCE.hep2, Pair(sender, buffer))
            }
        }
    }
}