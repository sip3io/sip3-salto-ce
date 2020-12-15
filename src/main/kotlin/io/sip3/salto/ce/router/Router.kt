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
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.udf.UdfExecutor
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

    val packetsRouted = Metrics.counter("packets_routed")

    lateinit var udfExecutor: UdfExecutor

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

        udfExecutor = UdfExecutor(vertx)

        vertx.eventBus().localConsumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
            try {
                val (sender, packet) = event.body()
                handle(sender, packet)
            } catch (e: Exception) {
                logger.error("Router 'handle()' failed.", e)
            }
        }
    }

    open fun handle(sender: Address, packet: Packet) {
        // Map all addresses to host
        mapAddressToHost(sender)
        mapAddressToHost(packet.srcAddr)
        mapAddressToHost(packet.dstAddr)

        udfExecutor.execute(RoutesCE.packet_udf,
            // Prepare UDF payload
            mappingFunction = {
                mutableMapOf<String, Any>().apply {
                    put("sender_addr", sender.addr)
                    put("sender_port", sender.port)
                    sender.host?.let { put("sender_host", it) }

                    put("payload", mutableMapOf<String, Any>().apply {
                        val src = packet.srcAddr
                        put("src_addr", src.addr)
                        put("src_port", src.port)
                        src.host?.let { put("src_host", it) }

                        val dst = packet.dstAddr
                        put("dst_addr", dst.addr)
                        put("dst_port", dst.port)
                        dst.host?.let { put("dst_host", it) }
                    })
                }
            },
            // Handle UDF result
            completionHandler = { asr ->
                val (result, _) = asr.result()
                if (result) {
                    route(packet)
                }
            })
    }

    open fun mapAddressToHost(address: Address) {
        (hostMap[address.addr] ?: hostMap["${address.addr}:${address.port}"])?.let { address.host = it }
    }

    open fun route(packet: Packet) {
        val route = when (packet.protocolCode) {
            PacketTypes.SIP -> RoutesCE.sip
            PacketTypes.RTCP -> RoutesCE.rtcp
            PacketTypes.RTPR -> RoutesCE.rtpr
            else -> null
        }

        if (route != null) {
            packetsRouted.increment()
            writeAttributes(packet)
            vertx.eventBus().localRequest<Any>(route, packet)
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

        vertx.eventBus().localRequest<Any>(RoutesCE.attributes, Pair("ip", attributes))
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

    open fun mapHostToAddr(host: JsonObject, type: String): MutableMap<String, String> {
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