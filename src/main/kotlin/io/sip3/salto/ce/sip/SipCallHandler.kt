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

import gov.nist.javax.sip.message.SIPMessage
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.cseqMethod
import io.sip3.salto.ce.util.statusCode
import io.sip3.salto.ce.util.transactionId
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles SIP calls
 */
open class SipCallHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "sip_call"

        const val ATTEMPTS = PREFIX + "_attempts"
        const val DURATION = PREFIX + "_duration"
        const val TRYING_DELAY = PREFIX + "_trying-delay"
        const val SETUP_TIME = PREFIX + "_setup-time"
        const val ESTABLISH_TIME = PREFIX + "_establish-time"
        const val DISCONNECT_TIME = PREFIX + "_disconnect-time"
        const val APPROACHING = PREFIX + "_approaching"
        const val ESTABLISHED = PREFIX + "_established"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var expirationDelay: Long = 1000
    private var aggregationTimeout: Long = 60000
    private var terminationTimeout: Long = 5000
    private var durationTimeout: Long = 3600000
    private var exclusions = emptyList<String>()

    private var inviteTransactions = mutableMapOf<String, SipTransaction>()
    private var byeTransactions = mutableMapOf<String, SipTransaction>()

    private var activeSessions = mutableMapOf<String, Map<String, SipSession>>()

    override fun start() {
        config().let { config ->
            config.getString("time-suffix")?.let {
                timeSuffix = DateTimeFormatter.ofPattern(it)
            }

            config.getJsonObject("sip")?.getJsonObject("call")?.let { config ->
                config.getLong("expiration-delay")?.let {
                    expirationDelay = it
                }
                config.getLong("aggregation-timeout")?.let {
                    aggregationTimeout = it
                }
                config.getLong("termination-timeout")?.let {
                    terminationTimeout = it
                }
                config.getLong("duration-timeout")?.let {
                    durationTimeout = it
                }
                config.getJsonArray("exclusions")?.let {
                    exclusions = it.map(Any::toString)
                }
            }
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredTransactions()
        }

        val index = config().getInteger("index")
        vertx.eventBus().localConsumer<Pair<Packet, SIPMessage>>(PREFIX + "_$index") { event ->
            try {
                val (packet, message) = event.body()
                handle(packet, message)
            } catch (e: Exception) {
                logger.error("SipCallHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(packet: Packet, message: SIPMessage) {
        val tid = message.transactionId()

        val cseqMethod = message.cseqMethod()
        when (cseqMethod) {
            "INVITE" -> {
                inviteTransactions.getOrPut(tid) { SipTransaction() }.addMessage(packet, message)
            }
            "ACK" -> {
                // To simplify call aggregation we decided to skip ACK transaction.
                // Moreover, skipped ACK transaction will not affect final result.
            }
            "BYE" -> {
                byeTransactions.getOrPut(tid) { SipTransaction() }.addMessage(packet, message)
            }
        }
    }

    open fun terminateExpiredTransactions() {
        val now = System.currentTimeMillis()

        inviteTransactions.toMap()
                .filterValues { transaction ->
                    val terminatedAt = transaction.terminatedAt
                    // Check if transaction was terminated and `termination-timeout` has passed
                    // Otherwise, check if `aggregation-timout` has passed
                    return@filterValues if (terminatedAt != null) {
                        terminatedAt + terminationTimeout < now
                    } else {
                        val attributes = transaction.attributes
                                .toMutableMap()
                                .apply {
                                    remove("retransmits")
                                    exclusions.forEach { remove(it) }
                                }

                        Metrics.counter(APPROACHING, attributes).increment()

                        transaction.createdAt + aggregationTimeout < now
                    }
                }
                .forEach { (tid, transaction) ->
                    inviteTransactions.remove(tid)
                    terminateInviteTransaction(transaction)
                }

        byeTransactions.toMap()
                .filterValues { transaction ->
                    val terminatedAt = transaction.terminatedAt
                    // Check if transaction was terminated and `termination-timeout` has passed
                    // Otherwise, check if `aggregation-timout` has passed
                    return@filterValues if (terminatedAt != null) {
                        terminatedAt + terminationTimeout < now
                    } else {
                        transaction.createdAt + aggregationTimeout < now
                    }
                }
                .forEach { (tid, transaction) ->
                    inviteTransactions.remove(tid)
                    terminateByeTransaction(transaction)
                }
    }

    open fun terminateInviteTransaction(transaction: SipTransaction) {
        val session = activeSessions.get(transaction.callId)
                ?.get(transaction.legId)
                ?: SipSession()

        session.addInviteTransaction(transaction)

        when (session.state) {
            SipSession.REDIRECTED, SipSession.CANCELED, SipSession.FAILED -> {
                // TODO...
            }
            else -> {
                // TODO...
            }
        }

        calculateInviteTransactionMetrics(transaction)
    }

    open fun calculateInviteTransactionMetrics(transaction: SipTransaction) {
        val attributes = transaction.attributes
                .toMutableMap()
                .apply {
                    exclusions.forEach { remove(it) }
                    remove("retransmits")
                }

        val createdAt = transaction.createdAt

        transaction.tryingAt?.let { tryingAt ->
            if (createdAt < tryingAt) {
                Metrics.timer(TRYING_DELAY, attributes).record(tryingAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }
        transaction.ringingAt?.let { ringingAt ->
            if (createdAt < ringingAt) {
                Metrics.timer(SETUP_TIME, attributes).record(ringingAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }
        val response = transaction.response
        if (response != null && response.statusCode() == 200) {
            transaction.terminatedAt?.let { terminatedAt ->
                if (createdAt < terminatedAt) {
                    Metrics.timer(ESTABLISH_TIME, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    open fun terminateByeTransaction(transaction: SipTransaction) {
        // TODO...

        calculateByeTransactionMetrics(transaction)
    }

    open fun calculateByeTransactionMetrics(transaction: SipTransaction) {
        val attributes = transaction.attributes
                .toMutableMap()
                .apply {
                    exclusions.forEach { remove(it) }
                    remove("retransmits")
                }

        val createdAt = transaction.createdAt

        transaction.terminatedAt?.let { terminatedAt ->
            if (createdAt < terminatedAt) {
                Metrics.timer(DISCONNECT_TIME, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }
    }

    open fun writeToDatabase(prefix: String, session: SipSession, replace: Boolean = false) {
        val collection = PREFIX + "_index_" + timeSuffix.format(session.createdAt)

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                put("state", session.state)

                put("created_at", session.createdAt)
                put("terminated_at", session.terminatedAt)

                val src = session.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = session.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("caller", session.caller)
                put("callee", session.callee)
                put("call_id", session.callId)

                session.setupTime?.let { put("setup_time", it) }
                session.duration?.let { put("duration", it) }

                // TODO...
            })
            if (replace) {
                // TODO...
                put("upsert", true)
            }
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}