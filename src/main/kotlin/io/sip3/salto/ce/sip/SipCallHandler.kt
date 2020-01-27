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
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.cseqMethod
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
    private var transactionExclusions = emptyList<String>()
    private var recordCallUsersAttributes = false

    private var inviteTransactions = mutableMapOf<String, SipTransaction>()
    private var byeTransactions = mutableMapOf<String, SipTransaction>()

    private var activeSessions = mutableMapOf<String, MutableMap<String, SipSession>>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("call")?.let { config ->
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
            config.getJsonArray("transaction-exclusions")?.let {
                transactionExclusions = it.map(Any::toString)
            }
        }
        config().getJsonObject("attributes")?.getBoolean("record-call-users")?.let {
            recordCallUsersAttributes = it
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredTransactions()
            terminateExpiredCallSessions()
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
        when (message.cseqMethod()) {
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

        inviteTransactions.filterValues { transaction ->
            val isExpired = (transaction.terminatedAt?.let { it + terminationTimeout } ?: transaction.createdAt + aggregationTimeout) < now

            if (!isExpired) {
                val attributes = excludeSessionAttributes(transaction.attributes)
                Metrics.counter(APPROACHING, attributes).increment()
            }

            return@filterValues isExpired
        }.forEach { (tid, transaction) ->
            inviteTransactions.remove(tid)
            terminateInviteTransaction(transaction)
        }

        byeTransactions.filterValues { transaction ->
            return@filterValues (transaction.terminatedAt?.let { it + terminationTimeout } ?: transaction.createdAt + aggregationTimeout) < now
        }.forEach { (tid, transaction) ->
            byeTransactions.remove(tid)
            terminateByeTransaction(transaction)
        }
    }

    open fun terminateInviteTransaction(transaction: SipTransaction) {
        val session = activeSessions.get(transaction.callId)?.get(transaction.legId) ?: SipSession()

        session.addInviteTransaction(transaction)

        when (session.state) {
            SipSession.REDIRECTED, SipSession.CANCELED, SipSession.FAILED -> {
                terminateCallSession(session)
            }
            SipSession.ANSWERED -> {
                writeAttributes(session)
                writeToDatabase(PREFIX, session)
                activeSessions.getOrPut(transaction.callId) { mutableMapOf() }.put(transaction.legId, session)
            }
            else -> {
                activeSessions.getOrPut(transaction.callId) { mutableMapOf() }.put(transaction.legId, session)
            }
        }

        calculateInviteTransactionMetrics(transaction)
    }

    open fun calculateInviteTransactionMetrics(transaction: SipTransaction) {
        val createdAt = transaction.createdAt

        val attributes = excludeSessionAttributes(transaction.attributes)
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
        if (response?.statusCode == 200) {
            transaction.terminatedAt?.let { terminatedAt ->
                if (createdAt < terminatedAt) {
                    Metrics.timer(ESTABLISH_TIME, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    open fun terminateByeTransaction(transaction: SipTransaction) {
        activeSessions.get(transaction.callId)?.let { sessions ->
            val session = sessions[transaction.legId]
            if (session != null) {
                // In case of B2BUA we'll terminate particular call leg
                session.addByeTransaction(transaction)
            } else {
                // In case of SIP Proxy we'll check and terminate all legs related to the call
                sessions.forEach { (_, session) ->
                    if (session.caller == transaction.caller && session.callee == transaction.callee) {
                        session.addByeTransaction(transaction)
                    }
                }
            }
        }

        calculateByeTransactionMetrics(transaction)
    }

    open fun calculateByeTransactionMetrics(transaction: SipTransaction) {
        val createdAt = transaction.createdAt

        val attributes = excludeSessionAttributes(transaction.attributes)
        transaction.terminatedAt?.let { terminatedAt ->
            if (createdAt < terminatedAt) {
                Metrics.timer(DISCONNECT_TIME, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }
    }

    open fun terminateExpiredCallSessions() {
        val now = System.currentTimeMillis()

        activeSessions.filterValues { sessions ->
            sessions.filterValues { session ->
                val isExpired = (session.terminatedAt?.let { it + terminationTimeout }
                        ?: session.answeredAt?.let { it + durationTimeout } ?: session.createdAt + aggregationTimeout) < now

                if (!isExpired && session.state == SipSession.ANSWERED) {
                    val attributes = excludeSessionAttributes(session.attributes)
                    Metrics.counter(ESTABLISHED, attributes).increment()
                }

                return@filterValues isExpired
            }.forEach { (sid, session) ->
                sessions.remove(sid)
                terminateCallSession(session)
            }
            return@filterValues sessions.isEmpty()
        }.forEach { (callId, _) ->
            activeSessions.remove(callId)
        }
    }

    open fun terminateCallSession(session: SipSession) {
        if (session.terminatedAt == null) {
            session.terminatedAt = System.currentTimeMillis()
        }

        val state = session.state
        if (state == SipSession.ANSWERED && session.duration == null) {
            session.attributes[Attributes.expired] = true
        }

        writeAttributes(session)
        writeToDatabase(PREFIX, session, replace = (session.state == SipSession.ANSWERED))
        calculateCallSessionMetrics(session)
    }

    open fun calculateCallSessionMetrics(session: SipSession) {
        val attributes = session.attributes
                .toMutableMap()
                .apply {
                    put(Attributes.state, session.state)
                    remove(Attributes.caller)
                    remove(Attributes.callee)
                    remove(Attributes.x_call_id)
                }

        Metrics.counter(ATTEMPTS, attributes).increment()

        val duration = session.duration
        if (duration != null) {
            Metrics.summary(DURATION, attributes).record(duration.toDouble())
        }
    }

    open fun writeAttributes(session: SipSession) {
        val attributes = session.attributes
                .toMutableMap()
                .apply {
                    remove(Attributes.src_host)
                    remove(Attributes.dst_host)

                    put(Attributes.method, "INVITE")
                    put(Attributes.state, session.state)

                    put(Attributes.call_id, "")
                    remove(Attributes.x_call_id)

                    val caller = get(Attributes.caller) ?: session.caller
                    put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                    val callee = get(Attributes.callee) ?: session.callee
                    put(Attributes.callee, if (recordCallUsersAttributes) callee else "")

                    session.duration?.let { put(Attributes.duration, it) }
                    session.setupTime?.let { put(Attributes.setup_time, it) }
                    session.establishTime?.let { put(Attributes.establish_time, it) }
                }

        vertx.eventBus().send(RoutesCE.attributes, Pair("sip", attributes), USE_LOCAL_CODEC)
    }

    open fun writeToDatabase(prefix: String, session: SipSession, replace: Boolean = false) {
        val collection = prefix + "_index_" + timeSuffix.format(session.createdAt)

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

                session.duration?.let { put("duration", it) }
                session.setupTime?.let { put("setup_time", it) }
                session.establishTime?.let { put("establish_time", it) }

                session.attributes.forEach { (name, value) -> put(name, value) }
            })
            if (replace) {
                put("type", "REPLACE")
                put("filter", JsonObject().apply {
                    put("created_at", session.createdAt)
                    put("call_id", session.callId)
                    put("src_addr", session.srcAddr.addr)
                    put("dst_addr", session.dstAddr.addr)
                })
                put("upsert", true)
            }
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }

    private fun excludeSessionAttributes(attributes: Map<String, Any>): Map<String, Any> {
        return attributes.toMutableMap().apply {
            remove(Attributes.caller)
            remove(Attributes.callee)
            remove(Attributes.x_call_id)
            remove(Attributes.retransmits)
            transactionExclusions.forEach { remove(it) }
        }
    }
}