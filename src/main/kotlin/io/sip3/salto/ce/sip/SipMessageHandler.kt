/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

import gov.nist.javax.sip.message.MessageFactoryImpl
import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import io.sip3.commons.SipMethods
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.*
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Parses SIP messages, calculates related metrics and saves payload to `raw` collection
 */
open class SipMessageHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val SIP_METHODS = SipMethods.values().map(Any::toString).toSet()
    }

    // ISO-8859-1 required in case of SIP-I (to parse binary ISUP)
    init {
        StringMsgParser.setComputeContentLengthFromMessage(true)
        MessageFactoryImpl().setDefaultContentEncodingCharset(Charsets.ISO_8859_1.name())
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var checkUdfPeriod: Long = 30000
    private var executionUdfTimeout: Long = 100
    private var exclusions = emptySet<String>()
    private var instances = 1

    private var sendToUdf = false

    private val packetsProcessed = Metrics.counter("packets_processed", mapOf("proto" to "sip"))

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("message")?.getJsonArray("exclusions")?.let {
            exclusions = it.map(Any::toString).toSet()
        }
        config().getJsonObject("udf")?.let { config ->
            config.getLong("check-period")?.let {
                checkUdfPeriod = it
            }
            config.getLong("execution-timeout")?.let {
                executionUdfTimeout = it
            }
        }
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        checkUserDefinedFunction()

        vertx.eventBus().localConsumer<Packet>(RoutesCE.sip) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("SipMessageHandler 'handle()' failed.", e)
            }
        }
    }

    open fun checkUserDefinedFunction() {
        if (!sendToUdf) {
            sendToUdf = vertx.eventBus().consumes(RoutesCE.sip_message_udf)
            if (!sendToUdf) {
                vertx.setTimer(checkUdfPeriod) { checkUserDefinedFunction() }
            }
        }
    }

    open fun handle(packet: Packet) {
        packetsProcessed.increment()

        val message: SIPMessage? = try {
            StringMsgParser().parseSIPMessage(packet.payload, true, false, null)
        } catch (e: Exception) {
            logger.debug("StringMsgParser `parseSIPMessage()` failed.\n $packet")
            return
        }

        if (message != null && validate(message)) {
            val cseqMethod = message.cseqMethod()

            if (SIP_METHODS.contains(cseqMethod) && !exclusions.contains(cseqMethod)) {
                callUserDefinedFunction(packet, message)

                val prefix = prefix(cseqMethod!!)
                writeToDatabase(prefix, packet, message)
                calculateSipMessageMetrics(prefix, packet, message)

                route(prefix, message)?.let {
                    vertx.eventBus().send(it, Pair(packet, message), USE_LOCAL_CODEC)
                }
            }
        }
    }

    open fun validate(message: SIPMessage): Boolean {
        return message.callId() != null
                && message.toUri() != null && message.fromUri() != null
    }

    open fun callUserDefinedFunction(packet: Packet, message: SIPMessage) {
        if (sendToUdf) {
            val udf = mutableMapOf<String, Any>().apply {
                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("payload", message.headersMap())
                put("attributes", mutableMapOf<String, Any>())
            }

            GlobalScope.launch(vertx.dispatcher()) {
                val result = withTimeoutOrNull(executionUdfTimeout) {
                    vertx.eventBus().requestAwait<Boolean>(RoutesCE.sip_message_udf, udf, USE_LOCAL_CODEC)
                }

                if (result != null) {
                    (udf["attributes"] as? Map<String, Any>)?.forEach { (k, v) ->
                        when (v) {
                            is String, is Boolean -> packet.attributes[k] = v
                            else -> logger.warn("UDF attribute $k will be skipped due to unsupported value type.")
                        }
                    }
                } else {
                    logger.warn("UDF call took more than ${executionUdfTimeout}ms.")
                }
            }
        }
    }

    open fun prefix(cseqMethod: String): String {
        return when (cseqMethod) {
            "REGISTER", "NOTIFY", "MESSAGE", "OPTIONS", "SUBSCRIBE" -> RoutesCE.sip + "_${cseqMethod.toLowerCase()}"
            else -> RoutesCE.sip + "_call"
        }
    }

    open fun route(prefix: String, message: SIPMessage): String? {
        return when (prefix) {
            RoutesCE.sip + "_call" -> {
                val index = message.callId().hashCode()
                prefix + "_${abs(index % instances)}"
            }
            RoutesCE.sip + "_register" -> {
                // RFC-3261 10.2: The To header field contains the address of record
                // whose registration is to be created, queried, or modified.
                val index = message.toUri().hashCode()
                prefix + "_${abs(index % instances)}"
            }
            else -> null
        }
    }

    open fun calculateSipMessageMetrics(prefix: String, packet: Packet, message: SIPMessage) {
        val attributes = packet.attributes
                .apply {
                    packet.srcAddr.host?.let { put(Attributes.src_host, it) }
                    packet.dstAddr.host?.let { put(Attributes.dst_host, it) }
                }
                .toMutableMap()
                .apply {
                    message.statusCode()?.let {
                        put("status_type", "${it / 100}xx")
                        put("status_code", it)
                    }
                    message.method()?.let { put("method", it) }
                    message.cseqMethod()?.let { put("cseq_method", it) }
                }

        Metrics.counter(prefix + "_messages", attributes).increment()
    }

    open fun writeToDatabase(prefix: String, packet: Packet, message: SIPMessage) {
        val collection = prefix + "_raw_" + timeSuffix.format(packet.timestamp)

        val document = JsonObject().apply {
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

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}