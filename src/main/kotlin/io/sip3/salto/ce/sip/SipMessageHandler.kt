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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import io.sip3.commons.SipMethods
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.udf.UdfExecutor
import io.sip3.salto.ce.util.*
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import javax.sip.header.ExtensionHeader
import kotlin.math.abs

/**
 * Parses SIP messages, calculates related metrics and saves payload to `raw` collection
 */
@Instance
open class SipMessageHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val SIP_METHODS = SipMethods.values().map(Any::toString).toSet()
    }

    private var instances = 1
    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var exclusions = emptySet<String>()
    private var extensionHeaders = emptySet<String>()
    private var xCorrelationHeader = "X-Call-ID"

    private val packetsProcessed = Metrics.counter("packets_processed", mapOf("proto" to "sip"))

    private lateinit var parser: SipMessageParser
    private lateinit var udfExecutor: UdfExecutor

    override fun start() {
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("message")?.let { config ->
            config.getJsonArray("exclusions")?.let {
                exclusions = it.map(Any::toString).toSet()
            }
            config.getJsonArray("extension-headers")?.let {
                extensionHeaders = it.map(Any::toString).toSet()
            }
            config.getString("x-correlation-header")?.let {
                xCorrelationHeader = it
            }
        }

        parser = SipMessageParser(extensionHeaders)
        udfExecutor = UdfExecutor(vertx)

        vertx.eventBus().localConsumer<Packet>(RoutesCE.sip) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("SipMessageHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(packet: Packet) {
        packetsProcessed.increment()

        parser.parse(packet).forEach { (pkt, message) ->
            if (validate(message)) {
                val cseqMethod = message.cseqMethod()
                if (cseqMethod != null && SIP_METHODS.contains(cseqMethod) && !exclusions.contains(cseqMethod)) {
                    handleSipMessage(pkt, message)
                }
            }
        }
    }

    open fun validate(message: SIPMessage): Boolean {
        return message.callId() != null
                && message.toUserOrNumber() != null && message.fromUserOrNumber() != null
    }

    open fun handleSipMessage(packet: Packet, message: SIPMessage) {
        // Find `x-correlation-header`
        (message.getHeader(xCorrelationHeader) as? ExtensionHeader)?.let { header ->
            packet.attributes[Attributes.x_call_id] = header.value
        }

        udfExecutor.execute(RoutesCE.sip_message_udf,
                // Prepare UDF payload
                mappingFunction = {
                    mutableMapOf<String, Any>().apply {
                        val src = packet.srcAddr
                        put("src_addr", src.addr)
                        put("src_port", src.port)
                        src.host?.let { put("src_host", it) }

                        val dst = packet.dstAddr
                        put("dst_addr", dst.addr)
                        put("dst_port", dst.port)
                        dst.host?.let { put("dst_host", it) }

                        put("payload", message.headersMap())
                    }
                },
                // Handle UDF result
                completionHandler = { asr ->
                    val (result, attributes) = asr.result()
                    if (result) {
                        attributes.forEach { (k, v) -> packet.attributes[k] = v }

                        val cseqMethod = message.cseqMethod()
                        val prefix = when (cseqMethod) {
                            "REGISTER", "NOTIFY", "MESSAGE", "OPTIONS", "SUBSCRIBE" -> RoutesCE.sip + "_${cseqMethod.toLowerCase()}"
                            else -> RoutesCE.sip + "_call"
                        }

                        routeSipMessage(prefix, packet, message)
                        calculateSipMessageMetrics(prefix, packet, message)
                    }
                })
    }

    open fun routeSipMessage(prefix: String, packet: Packet, message: SIPMessage) {
        val index = message.callId().hashCode()
        val route = RoutesCE.sip + "_transaction_${abs(index % instances)}"

        writeToDatabase(prefix, packet, message)
        vertx.eventBus().localRequest<Any>(route, Pair(packet, message))
    }

    open fun calculateSipMessageMetrics(prefix: String, packet: Packet, message: SIPMessage) {
        val attributes = packet.attributes
                .toMutableMap()
                .apply {
                    packet.srcAddr.host?.let { put(Attributes.src_host, it) }
                    packet.dstAddr.host?.let { put(Attributes.dst_host, it) }
                    message.statusCode()?.let {
                        put("status_type", "${it / 100}xx")
                        put("status_code", it)
                    }
                    message.method()?.let { put("method", it) }
                    message.cseqMethod()?.let { put("cseq_method", it) }
                    remove(Attributes.caller)
                    remove(Attributes.callee)
                    remove(Attributes.x_call_id)
                }

        Metrics.counter(prefix + "_messages", attributes).increment()
    }

    open fun writeToDatabase(prefix: String, packet: Packet, message: SIPMessage) {
        val collection = prefix + "_raw_" + timeSuffix.format(packet.timestamp)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                val timestamp = packet.timestamp
                put("created_at", timestamp.time)
                put("nanos", timestamp.nanos)

                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("call_id", message.callId())
                put("raw_data", String(packet.payload, Charsets.ISO_8859_1))
            })
        }

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}