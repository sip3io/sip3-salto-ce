/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

package io.sip3.salto.ce.socket

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.MongoClient
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.host.HostRegistry
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.SocketAddress
import mu.KotlinLogging
import java.net.URI

/**
 * Management socket
 */
@Instance(singleton = true)
@ConditionalOnProperty("/management")
open class ManagementSocket : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val TYPE_SHUTDOWN = "shutdown"
        const val TYPE_REGISTER = "register"
        const val TYPE_MEDIA_CONTROL = "media_control"
    }

    private var client: io.vertx.ext.mongo.MongoClient? = null

    private lateinit var hostRegistry: HostRegistry

    private lateinit var uri: URI
    private var expirationDelay: Long = 60000
    private var expirationTimeout: Long = 120000
    private var publishMediaControlMode = 0

    private lateinit var socket: DatagramSocket

    private val remoteHosts = mutableMapOf<String, RemoteHost>()
    private var sendSdpSessions = false

    override fun start() {
        config().getJsonObject("mongo")?.let { config ->
            client = MongoClient.createShared(vertx, config)
        }

        hostRegistry = HostRegistry.getInstance(vertx, config())

        config().getJsonObject("management").let { config ->
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))

            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("expiration-timeout")?.let {
                expirationTimeout = it
            }
            config.getInteger("publish-media-control-mode")?.let {
                publishMediaControlMode = it
            }
        }

        startUdpServer()

        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()

            remoteHosts.filterValues { it.lastUpdate + expirationTimeout < now }
                .forEach { (name, remoteHost) ->
                    logger.info { "Expired: $remoteHost" }
                    remoteHosts.remove(name)
                }

            sendSdpSessions = remoteHosts.any { (_, host) -> host.mediaEnabled }
        }

        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            try {
                val mediaControl = event.body()
                publishMediaControl(mediaControl)
            } catch (e: Exception) {
                logger.error(e) { "ManagementSocket 'publishMediaControl()' failed." }
            }
        }
    }

    open fun startUdpServer() {
        val options = DatagramSocketOptions().apply {
            isIpV6 = uri.host.matches(Regex("\\[.*]"))
        }

        socket = vertx.createDatagramSocket(options)

        socket.handler { packet ->
            val socketAddress = packet.sender()
            val buffer = packet.data()
            try {
                val message = buffer.toJsonObject()
                handle(socketAddress, message)
            } catch (e: Exception) {
                logger.error(e) { "ManagementSocket 'handle()' failed." }
            }
        }

        socket.listen(uri.port, uri.host) { connection ->
            if (connection.failed()) {
                logger.error(connection.cause()) { "UDP connection failed. URI: $uri" }
                throw connection.cause()
            }
            logger.info { "Listening on $uri" }
        }
    }

    open fun handle(socketAddress: SocketAddress, message: JsonObject) {
        val type = message.getString("type")
        val payload = message.getJsonObject("payload")

        when (type) {
            TYPE_REGISTER -> {
                val config = payload.getJsonObject("config")

                remoteHosts.computeIfAbsent(config?.getJsonObject("host")?.getString("name") ?: payload.getString("name")) { name ->
                    logger.info { "Registered: $payload" }

                    val host = socketAddress.host()
                    val port = socketAddress.port()
                    val uri = URI("${uri.scheme}://$host:$port")

                    return@computeIfAbsent RemoteHost(name, uri)
                }.apply {
                    config?.getJsonObject("host")?.let { hostRegistry.save(it) }

                    val rtpEnabled = config?.getJsonObject("rtp")?.getBoolean("enabled") ?: false
                    val rtcpEnabled = config?.getJsonObject("rtcp")?.getBoolean("enabled") ?: false
                    mediaEnabled = rtpEnabled || rtcpEnabled
                    sendSdpSessions = sendSdpSessions || mediaEnabled

                    lastUpdate = System.currentTimeMillis()
                }
            }
            TYPE_SHUTDOWN -> {
                val name = payload.getString("name")
                remoteHosts[name]?.apply {
                    logger.info { "Shutting down the `$name` via management socket..." }
                    socket.send(message.toBuffer(), uri.port, uri.host) {}
                }
            }
            else -> logger.error { "Unknown message type. Message: ${message.encodePrettily()}" }
        }
    }

    open fun publishMediaControl(mediaControl: MediaControl) {
        if (!sendSdpSessions) return

        val message = JsonObject().apply {
            put("type", TYPE_MEDIA_CONTROL)
            put("payload", JsonObject.mapFrom(mediaControl))
        }.toBuffer()

        when (publishMediaControlMode) {
            // Mode 0: Send to all hosts
            0 -> {
                remoteHosts.forEach { (_, host) -> sendMediaControlIfNeeded(host, message) }
            }
            // Mode 1: Send to media participants only
            1 -> {
                mediaControl.sdpSession.apply {
                    remoteHosts[hostRegistry.getHostName(src.addr)]?.let {
                        sendMediaControlIfNeeded(it, message)
                    }
                    remoteHosts[hostRegistry.getHostName(dst.addr)]?.let {
                        sendMediaControlIfNeeded(it, message)
                    }
                }
            }
        }
    }

    private fun sendMediaControlIfNeeded(host: RemoteHost, message: Buffer) {
        if (host.mediaEnabled) {
            try {
                socket.send(message, host.uri.port, host.uri.host) {}
            } catch (e: Exception) {
                logger.error(e) { "Socket 'send()' failed. URI: ${host.uri}" }
            }
        }
    }

    data class RemoteHost(val name: String, val uri: URI) {

        var lastUpdate: Long = System.currentTimeMillis()
        var mediaEnabled = false
    }
}