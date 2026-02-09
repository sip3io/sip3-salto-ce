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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.*
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * Handles SIP Transactions
 */
@Instance
open class SipTransactionHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val EXCLUDED_ATTRIBUTES = listOf(
            Attributes.caller,
            Attributes.callee,
            Attributes.x_call_id,
            Attributes.recording_mode,
            Attributes.debug
        )

        val PREFIX = RoutesCE.sip + "_transaction"

        const val RESPONSE_DELAY = "response-delay"

        // To simplify call aggregation we decided to skip ACK and CANCEL transaction.
        // Moreover, skipped ACK and CANCEL transactions will not affect final result.
        val EXCLUDED_METHODS = listOf("ACK", "CANCEL")
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var expirationDelay: Long = 1000
    private var responseTimeout: Long = 3000
    private var aggregationTimeout: Long = 60000
    private var terminationTimeout: Long = 4500
    private var saveSipMessagePayloadMode = 0

    private var allowEmptyUser = false

    private var recordIpAddressesAttributes = false
    private var recordCallUsersAttributes = false
    private var instances = 1

    private lateinit var transactions: PeriodicallyExpiringHashMap<String, SipTransaction>

    private lateinit var attributesRegistry: AttributesRegistry

    override fun start() {
        config().getString("time_suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("sip")?.getJsonObject("message")?.let { config ->
            config.getBoolean("allow_empty_user")?.let {
                allowEmptyUser = it
            }
        }

        config().getJsonObject("sip")?.getJsonObject("transaction")?.let { config ->
            config.getLong("expiration_delay")?.let {
                expirationDelay = it
            }
            config.getLong("response_timeout")?.let {
                responseTimeout = it
            }
            config.getLong("aggregation_timeout")?.let {
                aggregationTimeout = it
            }
            config.getLong("termination_timeout")?.let {
                terminationTimeout = it
            }
            config.getInteger("save_sip_message_payload_mode")?.let {
                saveSipMessagePayloadMode = it
            }
        }
        config().getJsonObject("attributes")?.let { config ->
            config.getBoolean("record_ip_addresses")?.let {
                recordIpAddressesAttributes = it
            }
            config.getBoolean("record_call_users")?.let {
                recordCallUsersAttributes = it
            }
        }
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        attributesRegistry = AttributesRegistry(vertx, config())

        transactions = PeriodicallyExpiringHashMap.Builder<String, SipTransaction>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, transaction -> terminateTransactionAt(transaction) }
            .onExpire { _, transaction -> routeTransaction(transaction) }
            .build(vertx)

        GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
            val index = vertx.sharedData().getLocalCounter(PREFIX).coAwait()
            vertx.eventBus().localConsumer<Pair<Packet, SIPMessage>>(PREFIX + "_${index.andIncrement.coAwait()}") { event ->
                try {
                    val (packet, message) = event.body()
                    handle(packet, message)
                } catch (e: Exception) {
                    logger.error("SipTransactionHandler 'handle()' failed.", e)
                }
            }
        }
    }

    open fun handle(packet: Packet, message: SIPMessage) {
        if (message.cseqMethod() in EXCLUDED_METHODS) {
            return
        }

        val transactionId = message.transactionId()

        val transaction = transactions.getOrPut(transactionId) { SipTransaction() }
        transactions.touch(transactionId)

        val extend = (saveSipMessagePayloadMode == 0) || (saveSipMessagePayloadMode == 1 && message is SIPRequest)
        transaction.addMessage(packet, message, extend, allowEmptyUser)

        // Send SDP
        if (transaction.cseqMethod == "INVITE" && transaction.request?.hasSdp() == true && transaction.response?.hasSdp() == true) {
            vertx.eventBus().localSend(RoutesCE.media + "_sdp", transaction)
        }
    }

    open fun terminateTransactionAt(transaction: SipTransaction): Long {
        // 1. Wait `termination-timeout` if transaction was terminated
        // 2. Wait `response-timeout` if transaction was created but hasn't received any response yet
        // 3. Wait `aggregation-timeout` if transaction was created and has received response with non final status code
        return transaction.terminatedAt?.let { it + terminationTimeout }
            ?: (transaction.createdAt + (transaction.establishedAt?.let { aggregationTimeout } ?: responseTimeout))
    }

    open fun routeTransaction(transaction: SipTransaction) {
        val prefix = when (transaction.cseqMethod) {
            "REGISTER", "NOTIFY", "MESSAGE", "OPTIONS", "SUBSCRIBE" -> RoutesCE.sip + "_${transaction.cseqMethod.lowercase()}"
            else -> RoutesCE.sip + "_call"
        }

        when (prefix) {
            RoutesCE.sip + "_call", RoutesCE.sip + "_register" -> {
                val index = transaction.callId.hashCode()
                val route = prefix + "_${abs(index % instances)}"
                vertx.eventBus().localSend(route, transaction)
            }
            else -> {
                writeAttributes(transaction)
                writeToDatabase(prefix, transaction)
                calculateTransactionMetrics(prefix, transaction)
            }
        }
    }

    private fun calculateTransactionMetrics(prefix: String, transaction: SipTransaction) {
        transaction.terminatedAt?.let { terminatedAt ->
            val attributes = transaction.attributes
                .toMetricsAttributes(EXCLUDED_ATTRIBUTES)
                .apply {
                    transaction.srcAddr.host?.let { put(Attributes.src_host, it) }
                    transaction.dstAddr.host?.let { put(Attributes.dst_host, it) }
                }

            Metrics.timer(prefix + "_$RESPONSE_DELAY", attributes).record(terminatedAt - transaction.createdAt, TimeUnit.MILLISECONDS)
        }
    }

    open fun writeAttributes(transaction: SipTransaction) {
        val attributes = transaction.attributes
            .toAttributes()
            .apply {
                put(Attributes.method, transaction.cseqMethod)
                put(Attributes.state, transaction.state)

                val src = transaction.srcAddr
                put(Attributes.src_addr, if (recordIpAddressesAttributes) src.addr else "")
                src.host?.let { put(Attributes.src_host, it) }

                val dst = transaction.dstAddr
                put(Attributes.dst_addr, if (recordIpAddressesAttributes) dst.addr else "")
                dst.host?.let { put(Attributes.dst_host, it) }

                val caller = get(Attributes.caller) ?: transaction.caller
                put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                val callee = get(Attributes.callee) ?: transaction.callee
                put(Attributes.callee, if (recordCallUsersAttributes) callee else "")

                put(Attributes.call_id, "")

                transaction.errorCode?.let { put(Attributes.error_code, it) }
                transaction.errorType?.let { put(Attributes.error_type, it) }

                put(Attributes.retransmits, transaction.retransmits)

                remove(Attributes.x_call_id)
                remove(Attributes.recording_mode)
            }

        attributesRegistry.handle("sip", attributes)
    }

    open fun writeToDatabase(prefix: String, transaction: SipTransaction) {
        val collection = prefix + "_index_" + timeSuffix.format(transaction.createdAt)

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                put("state", transaction.state)

                put("created_at", transaction.createdAt)
                put("terminated_at", transaction.terminatedAt)

                val src = transaction.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = transaction.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("caller", transaction.attributes.remove(Attributes.caller) ?: transaction.caller)
                put("callee", transaction.attributes.remove(Attributes.callee) ?: transaction.callee)
                put("call_id", transaction.callId)

                transaction.errorCode?.let { put("error_code", it) }
                transaction.errorType?.let { put("error_type", it) }

                put("retransmits", transaction.retransmits)

                transaction.attributes
                    .toDatabaseAttributes()
                    .forEach { (name, value) -> put(name, value) }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, document))
    }
}