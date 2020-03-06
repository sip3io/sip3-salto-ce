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

package io.sip3.salto.ce.router

import io.sip3.commons.PacketTypes
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import mu.KotlinLogging
import org.apache.commons.net.util.SubnetUtils

/**
 * Routes packets by `protocolCode`
 */
@Instance
open class Router : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var client: MongoClient? = null
    private var updatePeriod: Long = 0
    private var recordIpAddressesAttributes = false

    open var hostMap = emptyMap<String, String>()

    override fun start() {
        config().getJsonObject("mongo")?.let { config ->
            client = MongoClient.createShared(vertx, JsonObject().apply {
                put("connection_string", config.getString("uri") ?: throw IllegalArgumentException("mongo.uri"))
                put("db_name", config.getString("db") ?: throw IllegalArgumentException("mongo.db"))
            })
            config.getLong("update-period")?.let { updatePeriod = it }
        }
        config().getJsonObject("attributes")?.getBoolean("record-ip-addresses")?.let {
            recordIpAddressesAttributes = it
        }

        if (client != null) {
            updateHostMap()
            if (updatePeriod > 0) {
                vertx.setPeriodic(updatePeriod) { updateHostMap() }
            }
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.router) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("Router 'handle()' failed.", e)
            }
        }
    }

    open fun handle(packet: Packet) {
        val src = packet.srcAddr
        (hostMap[src.addr] ?: hostMap["${src.addr}:${src.port}"])?.let { src.host = it }

        val dst = packet.dstAddr
        (hostMap[dst.addr] ?: hostMap["${dst.addr}:${dst.port}"])?.let { dst.host = it }

        route(packet)
    }

    open fun route(packet: Packet) {
        val route = when (packet.protocolCode) {
            PacketTypes.SIP -> RoutesCE.sip
            PacketTypes.RTPR -> RoutesCE.rtpr
            else -> null
        }

        if (route != null) {
            writeAttributes(packet)
            vertx.eventBus().send(route, packet, USE_LOCAL_CODEC)
        }
    }

    open fun updateHostMap() {
        client?.find("hosts", JsonObject()) { asr ->
            if (asr.failed()) {
                logger.error("MongoClient 'find()' failed.", asr.cause())
                return@find
            }
            val tmpHostMap = mutableMapOf<String, String>().apply {
                asr.result().forEach { host ->
                    try {
                        putAll(mapHostToAddr(host, type = "sip"))
                        putAll(mapHostToAddr(host, type = "media"))
                    } catch (e: Exception) {
                        logger.error("Router `mapHostToAddr()` failed. Host: $host")
                    }
                }
            }
            hostMap = tmpHostMap
        }
    }

    open fun writeAttributes(packet: Packet) {
        val attributes = mutableMapOf<String, Any>().apply {
            val src = packet.srcAddr
            put(Attributes.src_addr, if (recordIpAddressesAttributes) src.addr else "")
            src.host?.let { put(Attributes.src_host, it) }

            val dst = packet.dstAddr
            put(Attributes.dst_addr, if (recordIpAddressesAttributes) dst.addr else "")
            dst.host?.let { put(Attributes.dst_host, it) }
        }

        vertx.eventBus().send(RoutesCE.attributes, Pair("ip", attributes), USE_LOCAL_CODEC)
    }

    private fun mapHostToAddr(host: JsonObject, type: String): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            val name = host.getString("name")
            host.getJsonArray(type)?.forEach { addr ->
                addr as String
                put(addr, name)
                if (addr.contains("/")) {
                    SubnetUtils(addr).info
                            .allAddresses
                            .forEach { put(it, name) }
                }
            }
        }
    }
}