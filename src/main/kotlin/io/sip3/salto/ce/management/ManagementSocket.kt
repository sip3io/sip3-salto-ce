/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.management.component.ComponentRegistry
import io.sip3.salto.ce.management.host.HostRegistry
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
        const val TYPE_CONFIG_REQUEST = "config_request"
        const val TYPE_CONFIG = "config"
        const val TYPE_REGISTER = "register"
        const val TYPE_MEDIA_CONTROL = "media_control"
        const val TYPE_MEDIA_RECORDING_RESET = "media_recording_reset"
    }

    private lateinit var hostRegistry: HostRegistry
    private lateinit var componentRegistry: ComponentRegistry

    private lateinit var name: String
    private lateinit var uri: URI
    private var expirationDelay: Long = 60000
    private var expirationTimeout: Long = 120000
    private var publishMediaControlMode = 0

    private lateinit var socket: DatagramSocket

    private lateinit var components: PeriodicallyExpiringHashMap<String, Component>
    private val hostMapping = mutableMapOf<String, String>()

    private var mediaEnabledComponentsCounter: Int = 0

    override fun start() {
        hostRegistry = HostRegistry.getInstance(vertx, config())
        componentRegistry = ComponentRegistry.getInstance(vertx, config())

        name = config().getString("name")

        config().getJsonObject("management").let { config ->
            uri = URI(config.getString("uri") ?: throw IllegalArgumentException("uri"))

            config.getLong("expiration_delay")?.let {
                expirationDelay = it
            }
            config.getLong("expiration_timeout")?.let {
                expirationTimeout = it
            }
            config.getInteger("publish_media_control_mode")?.let {
                publishMediaControlMode = it
            }
        }

        startUdpServer()

        components = PeriodicallyExpiringHashMap.Builder<String, Component>()
            .delay(expirationDelay)
            .expireAt { _, component -> component.updatedAt + expirationTimeout }
            .onExpire { _, component ->
                logger.info { "Expired: $component" }
                if (component.mediaEnabled) mediaEnabledComponentsCounter--
            }
            .build(vertx)

        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            try {
                val mediaControl = event.body()
                publishMediaControl(mediaControl)
            } catch (e: Exception) {
                logger.error(e) { "ManagementSocket 'publishMediaControl()' failed." }
            }
        }

        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.media + "_recording_reset") { event ->
            try {
                val payload = event.body()
                publishMediaRecordingReset(payload)
            } catch (e: Exception) {
                logger.error(e) { "ManagementSocket 'publishMediaRecordingReset()' failed." }
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
        logger.trace { "Socket Address: $socketAddress, message: ${message.encode()}" }
        socketAddress.path()
        val host = socketAddress.host().substringBefore("%")
        val port = socketAddress.port()

        val type = message.getString("type")
        val payload = message.getJsonObject("payload")

        val now = System.currentTimeMillis()

        when (type) {
            TYPE_REGISTER -> {
                val deploymentId = payload.getString("deployment_id")
                val config = payload.getJsonObject("config")
                val senderUri = URI(uri.scheme, null, host, port, null, null, null)

                val componentName = config?.getString("name") ?: deploymentId
                val component = components.getOrPut(deploymentId) {
                    logger.info { "Registered from `$senderUri`: $payload" }
                    return@getOrPut Component(deploymentId, componentName)
                }.apply {
                    updatedAt = now
                    remoteUpdatedAt = payload.getLong("timestamp")

                    uri = senderUri
                    config?.getJsonObject("host")?.let { hostRegistry.save(it) }

                    config?.let {
                        this.config = it
                    }

                    val rtpOrRtcpEnabled = (config?.getJsonObject("rtp")?.getBoolean("enabled") ?: false)
                            || (config?.getJsonObject("rtcp")?.getBoolean("enabled") ?: false)

                    if (mediaEnabled != rtpOrRtcpEnabled) {
                        mediaEnabled = rtpOrRtcpEnabled
                        if (mediaEnabled) mediaEnabledComponentsCounter++ else mediaEnabledComponentsCounter--
                    }
                }

                save(component)
                config?.getJsonObject("host")?.getString("name")?.let { hostName ->
                    component.host = hostName
                    hostMapping.putIfAbsent(hostName, deploymentId)
                }
            }
            TYPE_CONFIG_REQUEST -> {
                val name = payload.getString("name")
                hostRegistry.getConfig(name)
                    .onFailure { logger.error(it) { "HostRegistry `getConfig()` failed. Name: $name" } }
                    .onSuccess { config ->
                        val response = JsonObject().apply {
                            put("type", TYPE_CONFIG)
                            put("payload", config ?: JsonObject())
                        }

                        socket.send(response.toBuffer(), port, host)
                    }
            }
            TYPE_SHUTDOWN -> {
                logger.info { "Handling `shutdown` command received via management socket: $message" }

                // Remove all components by `name`
                payload.getString("name")?.let { name ->
                    components.forEach { deploymentId, component ->
                        if (component.name == name) {
                            shutdown(deploymentId, message)
                        }
                    }
                }

                // Remove by `deployment_id`
                payload.getString("deployment_id")?.let { deploymentId ->
                    shutdown(deploymentId, message)
                }
            }
            else -> logger.error { "Unknown message type. Message: ${message.encodePrettily()}" }
        }
    }

    open fun shutdown(payload: JsonObject) {
        shutdown(payload.getString("deployment_id"), JsonObject().apply {
            put("type", TYPE_SHUTDOWN)
            put("payload", payload)
        })
    }

    open fun shutdown(deploymentId: String, message: JsonObject) {
        components.remove(deploymentId)?.apply {
            if (mediaEnabled) mediaEnabledComponentsCounter--
            host?.let { hostMapping.remove(it) }

            logger.info { "Shutting down component '${this.name}' via management socket..." }
            socket.send(message.toBuffer(), uri.port, uri.host) {}
        }
    }

    open fun save(component: Component) {
        val componentObject = JsonObject().apply {
            put("name", component.name)
            put("deployment_id", component.deploymentId)
            put("type", "captain")
            put("uri", component.uri.toString())
            put("connected_to", name)

            put("registered_at", component.registeredAt)
            put("updated_at", component.updatedAt)
            put("remote_updated_at", component.remoteUpdatedAt)

            put("config", component.config)
        }

        componentRegistry.save(componentObject)
    }

    open fun publishMediaControl(mediaControl: MediaControl) {
        if (mediaEnabledComponentsCounter <= 0) return

        val message = JsonObject().apply {
            put("type", TYPE_MEDIA_CONTROL)
            put("payload", JsonObject.mapFrom(mediaControl))
        }.toBuffer()

        when (publishMediaControlMode) {
            // Mode 0: Send to all hosts
            0 -> {
                components.forEach { _, host ->
                    sendMediaControlIfNeeded(host, message)
                }
            }
            // Mode 1: Send to media participants only
            1 -> {
                mediaControl.sdpSession.apply {
                    sendMediaControlIfNeeded(src.addr, message)
                    sendMediaControlIfNeeded(dst.addr, message)
                }
            }
        }
    }

    open fun publishMediaRecordingReset(payload: JsonObject) {
        val message = JsonObject().apply {
            put("type", TYPE_MEDIA_RECORDING_RESET)
            put("payload", payload)
        }.toBuffer()

        val deploymentId = payload.getString("deployment_id")
        components.forEach { _, component ->
            if (deploymentId != null && component.deploymentId != deploymentId) return@forEach

            try {
                socket.send(message, component.uri.port, component.uri.host) {}
            } catch (e: Exception) {
                logger.error(e) { "Socket 'send()' failed. URI: ${component.uri}" }
            }
        }
    }

    private fun sendMediaControlIfNeeded(component: Component, message: Buffer) {
        if (component.mediaEnabled) {
            try {
                socket.send(message, component.uri.port, component.uri.host) {}
            } catch (e: Exception) {
                logger.error(e) { "Socket 'send()' failed. URI: ${component.uri}" }
            }
        }
    }

    private fun sendMediaControlIfNeeded(addr: String, message: Buffer) {
        hostRegistry.getHostName(addr)
            ?.let { host -> hostMapping[host] }
            ?.let { deploymentId -> components.get(deploymentId) }
            ?.let { component -> sendMediaControlIfNeeded(component, message) }
    }

    open class Component(val deploymentId: String, val name: String) {

        var registeredAt = System.currentTimeMillis()
        var updatedAt: Long = System.currentTimeMillis()
        var remoteUpdatedAt: Long = 0L

        lateinit var uri: URI
        var config = JsonObject()

        var mediaEnabled = false
        var host: String? = null

        override fun toString(): String {
            return "Component(deploymentId=$deploymentId, name=$name, uri=$uri)"
        }
    }
}