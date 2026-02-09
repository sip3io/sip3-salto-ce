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

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
import io.sip3.commons.vertx.util.closeAndExitProcess
import io.sip3.commons.vertx.util.localPublish
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.management.component.ComponentRegistry
import io.sip3.salto.ce.management.host.HostRegistry
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.net.URI

/**
 * Management socket
 */
@Instance(singleton = true)
@ConditionalOnProperty("/management")
open class ManagementHandler : AbstractVerticle() {

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
    private var expirationDelay: Long = 60000
    private var expirationTimeout: Long = 120000
    private var publishMediaControlMode = 0

    private lateinit var saltoComponent: Component
    private lateinit var components: PeriodicallyExpiringHashMap<String, Component>
    private val hostMapping = mutableMapOf<String, String>()

    private var mediaEnabledComponentsCounter: Int = 0

    override fun start() {
        hostRegistry = HostRegistry.getInstance(vertx, config())
        componentRegistry = ComponentRegistry.getInstance(vertx, config())

        name = config().getString("name")

        config().getJsonObject("management").let { config ->
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

        components = PeriodicallyExpiringHashMap.Builder<String, Component>()
            .delay(expirationDelay)
            .expireAt { _, component -> component.updatedAt + expirationTimeout }
            .onExpire { _, component ->
                logger.info { "Expired: $component" }
                if (component.mediaEnabled) mediaEnabledComponentsCounter--
            }
            .build(vertx)

        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
            val newConfig = event.body()
            saltoComponent.config = newConfig
        }

        vertx.eventBus().localConsumer<Pair<URI, JsonObject>>(RoutesCE.management) { event ->
            try {
                val (uri, message) = event.body()
                val response = handle(uri, message)
                event.reply(response)
            } catch (e: Exception) {
                logger.error(e) { "ManagementHandler 'handle()' failed." }
            }
        }


        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            try {
                val mediaControl = event.body()
                publishMediaControl(mediaControl)
            } catch (e: Exception) {
                logger.error(e) { "ManagementHandler 'publishMediaControl()' failed." }
            }
        }

        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.media + "_recording_reset") { event ->
            try {
                val payload = event.body()
                publishMediaRecordingReset(payload)
            } catch (e: Exception) {
                logger.error(e) { "ManagementHandler 'publishMediaRecordingReset()' failed." }
            }
        }

        saltoComponent = Component(deploymentID(), name, "salto").apply {
            config = config()
            version = config().getString("version")
            remoteUpdatedAt = updatedAt
            uri = getManagementUri(config())
            mediaEnabled = false
        }

        vertx.setPeriodic(0L, expirationDelay) {
            val now = System.currentTimeMillis()
            saltoComponent.apply {
                remoteUpdatedAt = now
                updatedAt = now
            }

            save(saltoComponent)
        }
    }

    open fun handle(uri: URI, message: JsonObject): JsonObject? {
        logger.trace { "ManagementHandler `handle()`. URI $uri, message: ${message.encode()}" }

        val type = message.getString("type")
        val payload = message.getJsonObject("payload")

        val now = System.currentTimeMillis()

        when (type) {
            TYPE_REGISTER -> {
                val deploymentId = payload.getString("deployment_id") ?: payload.getString("name")
                val config = payload.getJsonObject("config")

                val componentName = config?.getString("name") ?: deploymentId
                val component = components.getOrPut(deploymentId) {
                    logger.info { "Registered from `$uri`: $payload" }
                    return@getOrPut Component(deploymentId, componentName, "captain")
                }.apply {
                    updatedAt = now
                    remoteUpdatedAt = payload.getLong("timestamp")
                    this.uri = uri

                    if (config != null) {
                        this.config = config
                        config.getString("version")?.let {
                            this.version = it
                        }

                        config.getJsonObject("host")?.let {
                            hostRegistry.saveAndRemoveDuplicates(it)
                        }
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

                val response = JsonObject().apply {
                    put("type", "register_response")
                    put("payload", JsonObject().apply {
                        put("status", "registered")
                        put("registered_at", System.currentTimeMillis())
                    })
                }

               return response
            }
            TYPE_CONFIG_REQUEST -> {
                val name = payload.getString("name")
                val config = getComponentConfig(name) ?: JsonObject()
                val response = JsonObject().apply {
                    put("type", TYPE_CONFIG)
                    put("payload", config)
                }

                return response
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

                return null
            }
            else -> {
                logger.error { "Unknown message type. Message: ${message.encodePrettily()}" }
                return null
            }
        }
    }

    open fun send(message: JsonObject, uri: URI) {
        send(message, listOf(uri))
    }

    open fun send(message: JsonObject, uris: List<URI>) {
        vertx.eventBus().localPublish(RoutesCE.management + "_send", Pair(message, uris))
    }

    open fun getComponentConfig(name: String): JsonObject? {
        return null
    }

    open fun shutdown(payload: JsonObject) {
        val deploymentId = payload.getString("deployment_id")
        if (deploymentId == saltoComponent.deploymentId) {
            val exitCode = payload.getInteger("exit_code") ?: -1
            logger.warn { "Shutting down the process with exit code: $exitCode" }
            vertx.closeAndExitProcess(exitCode)
            return
        }

        shutdown(deploymentId, JsonObject().apply {
            put("type", TYPE_SHUTDOWN)
            put("payload", payload)
        })
    }

    open fun shutdown(deploymentId: String, message: JsonObject) {
        components.remove(deploymentId)?.apply {
            if (mediaEnabled) mediaEnabledComponentsCounter--
            host?.let { hostMapping.remove(it) }

            logger.info { "Shutting down component '${this.name}' via management socket..." }
            send(message, uri)
        }
    }

    open fun save(component: Component) {
        val componentObject = JsonObject().apply {
            put("name", component.name)
            put("deployment_id", component.deploymentId)
            put("type", component.type)

            if (component.type == "salto") {
                val uriList = JsonArray()
                addUris(uriList, "management")
                addUris(uriList, "server")
                put("uri", uriList)
                val connectedToList = JsonArray()
                config().getJsonObject("mongo")?.let { mongo ->
                    mongo.getJsonObject("management")?.let { management ->
                        connectedToList.add(mongoConnectedTo(management))
                    }
                    connectedToList.add(mongoConnectedTo(mongo))
                }
                put("connected_to", connectedToList)
            } else {
                put("uri", JsonArray.of(component.uri.toString()))
                put("connected_to", JsonArray.of(name))
            }

            put("registered_at", component.registeredAt)
            put("updated_at", component.updatedAt)
            put("remote_updated_at", component.remoteUpdatedAt)

            put("config", component.config)
            put("version", component.version)
        }

        componentRegistry.save(componentObject)
    }

    private fun getManagementUri(config: JsonObject): URI {
        return config.getJsonObject("management")
            ?.let { management ->
                management.getString("uri")
                    ?: management.getJsonObject("udp")?.getString("uri")
                    ?: management.getJsonObject("tcp")?.getString("uri")
                    ?: management.getJsonObject("ws")?.getString("uri")
            }
            ?.let { URI(it) }
            ?: throw IllegalArgumentException("Salto must listen at least one URI")
    }

    private fun addUris(uriList: JsonArray, key: String) {
        config().getJsonObject(key)?.let { management ->
            management.getString("uri")?.let { uriList.add(it) }
            listOf("udp", "tcp", "ws", "wss").forEach { scheme ->
                management.getJsonObject(scheme)?.getString("uri")?.let { uriList.add(it) }
            }
        }
    }

    open fun publishMediaControl(mediaControl: MediaControl) {
        if (mediaEnabledComponentsCounter <= 0) return

        val message = JsonObject().apply {
            put("type", TYPE_MEDIA_CONTROL)
            put("payload", JsonObject.mapFrom(mediaControl))
        }

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
        }

        val deploymentId = payload.getString("deployment_id")
        components.values()
            .filter { deploymentId == null || it.deploymentId == deploymentId }
            .map { it.uri }
            .let { uris ->
                if (uris.isNotEmpty()) {
                    send(message, uris)
                }
            }
    }

    private fun sendMediaControlIfNeeded(component: Component, message: JsonObject) {
        if (component.mediaEnabled) {
            send(message, component.uri)
        }
    }

    private fun sendMediaControlIfNeeded(addr: String, message: JsonObject) {
        hostRegistry.getHostName(addr)
            ?.let { host -> hostMapping[host] }
            ?.let { deploymentId -> components.get(deploymentId) }
            ?.let { component -> sendMediaControlIfNeeded(component, message) }
    }

    private fun mongoConnectedTo(mongoConfig: JsonObject): String {
        val mongoUri = URI(mongoConfig.getString("uri"))
        return "${mongoUri.scheme}://${mongoUri.authority}/${mongoConfig.getString("db")}"
    }

    open class Component(val deploymentId: String, val name: String, val type: String) {

        var registeredAt = System.currentTimeMillis()
        var updatedAt: Long = System.currentTimeMillis()
        var remoteUpdatedAt: Long = 0L

        lateinit var uri: URI
        var version: String? = null
        var config = JsonObject()

        var mediaEnabled = false
        var host: String? = null

        override fun toString(): String {
            return "Component(deploymentId=$deploymentId, name=$name, uri=$uri)"
        }
    }
}