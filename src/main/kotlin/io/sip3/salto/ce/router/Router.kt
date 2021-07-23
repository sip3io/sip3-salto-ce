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

package io.sip3.salto.ce.router

import io.sip3.commons.PacketTypes
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.host.HostRegistry
import io.sip3.salto.ce.udf.UdfExecutor
import io.vertx.core.AbstractVerticle
import mu.KotlinLogging

/**
 * Routes packets by `protocolCode`
 */
@Instance
open class Router : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    val packetsRouted = Metrics.counter("packets_routed")

    lateinit var udfExecutor: UdfExecutor
    private lateinit var hostRegistry: HostRegistry

    override fun start() {
        udfExecutor = UdfExecutor(vertx)
        hostRegistry = HostRegistry.getInstance(vertx, config())

        vertx.eventBus().localConsumer<Pair<Address, List<Packet>>>(RoutesCE.router) { event ->
            val (sender, packets) = event.body()
            packets.forEach { packet ->
                try {
                    handle(sender, packet)
                } catch (e: Exception) {
                    logger.error("Router 'handle()' failed.", e)
                }
            }
        }
    }

    open fun handle(sender: Address, packet: Packet) {
        // Assign host to all addresses
        assignHost(sender)
        assignHost(packet.srcAddr)
        assignHost(packet.dstAddr)

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

    open fun assignHost(address: Address) {
        hostRegistry.getHostName(address.addr, address.port)?.let { address.host = it }
    }

    open fun route(packet: Packet) {
        val route = when (packet.protocolCode) {
            PacketTypes.SIP -> RoutesCE.sip
            PacketTypes.RTCP -> RoutesCE.rtcp
            PacketTypes.RTPR -> RoutesCE.rtpr
            PacketTypes.REC -> RoutesCE.rec
            else -> null
        }

        if (route != null) {
            packetsRouted.increment()
            vertx.eventBus().localSend(route, packet)
        }
    }
}