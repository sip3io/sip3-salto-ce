/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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
import io.vertx.core.AbstractVerticle
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.net.SocketAddress
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import mu.KotlinLogging
import java.net.URI

/**
 * Management socket
 */
@Instance(singleton = true)
@ConditionalOnProperty("/management")
class ManagementSocket : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val TYPE_MEDIA_CONTROL = "media_control"
        const val TYPE_REGISTER = "register"
    }

    private var client: io.vertx.ext.mongo.MongoClient? = null

    private lateinit var uri: URI
    private var expirationDelay: Long = 60000
    private var expirationTimeout: Long = 120000

    private val remoteHosts = mutableMapOf<String, RemoteHost>()
    private lateinit var socket: DatagramSocket
    private var sendSdpSessions = false

    override fun start() {
        config().getJsonObject("mongo")?.let { config ->
            client = MongoClient.createShared(vertx, config)
        }

        config().getJsonObject("management").let { config ->
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))
            config.getLong("expiration-delay")?.let { expirationDelay = it }
            config.getLong("expiration-timeout")?.let { expirationTimeout = it }
        }

        startUdpServer()

        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()

            remoteHosts.filterValues { it.lastUpdate + expirationTimeout < now }
                .forEach { (name, remoteHost) ->
                    logger.info { "Expired: $remoteHost" }
                    remoteHosts.remove(name)
                }

            sendSdpSessions = remoteHosts.any { it.value.mediaEnabled }
        }

        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            if (sendSdpSessions) {
                val mediaControl = event.body()
                publishMediaControl(mediaControl)
            }
        }
    }

    private fun startUdpServer() {
        socket = vertx.createDatagramSocket()

        socket.handler { packet ->
            val buffer = packet.data()
            val socketAddress = packet.sender()
            try {
                val message = buffer.toJsonObject()
                handle(message, socketAddress)
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

    private fun handle(message: JsonObject, socketAddress: SocketAddress) {
        val type = message.getString("type")
        val payload = message.getJsonObject("payload")

        when (type) {
            TYPE_REGISTER -> {
                val timestamp = payload.getLong("timestamp")
                val name = payload.getString("name")
                val config = payload.getJsonObject("config")

                remoteHosts.computeIfAbsent(name) {
                    val host = socketAddress.host()
                    val port = socketAddress.port()
                    val uri = URI("${uri.scheme}://$host:$port")

                    val remoteHost = RemoteHost(name, uri)
                    logger.info { "Registered: $remoteHost, Timestamp: $timestamp, Config:\n${config?.encodePrettily()}" }

                    config?.getJsonObject("host")?.let { updateHost(it) }

                    val rtpEnabled = config?.getJsonObject("rtp")?.getBoolean("enabled") ?: false
                    val rtcpEnabled = config?.getJsonObject("rtcp")?.getBoolean("enabled") ?: false
                    remoteHost.mediaEnabled = rtpEnabled || rtcpEnabled
                    sendSdpSessions = sendSdpSessions || remoteHost.mediaEnabled

                    return@computeIfAbsent remoteHost
                }.apply {
                    lastUpdate = System.currentTimeMillis()
                }
            }
            else -> logger.error { "Unknown message type. Message: ${message.encodePrettily()}" }
        }
    }

    private fun updateHost(host: JsonObject) {
        if (client != null) {
            val query = JsonObject().apply {
                put("name", host.getString("name"))
            }
            client!!.replaceDocumentsWithOptions("hosts", query, host, updateOptionsOf(upsert = true)) { asr ->
                if (asr.failed()) {
                    logger.error(asr.cause()) { "MongoClient 'replaceDocuments()' failed." }
                }
            }
        }
    }

    private fun publishMediaControl(mediaControl: MediaControl) {
        val buffer = JsonObject().apply {
            put("type", TYPE_MEDIA_CONTROL)
            put("payload", JsonObject.mapFrom(mediaControl))
        }.toBuffer()

        remoteHosts.forEach { (_, remoteHost) ->
            if (remoteHost.mediaEnabled) {
                try {
                    socket.send(buffer, remoteHost.uri.port, remoteHost.uri.host) {}
                } catch (e: Exception) {
                    logger.error(e) { "Socket 'send()' failed. URI: ${remoteHost.uri}" }
                }
            }
        }
    }

    data class RemoteHost(val name: String, val uri: URI) {

        var lastUpdate: Long = Long.MIN_VALUE
        var mediaEnabled = false
    }
}