/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.udf.UdfExecutor
import io.sip3.salto.ce.util.DurationUtil
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Handles SIP calls
 */
@Instance
open class SipCallHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        // Prefix
        const val PREFIX = "sip_call"

        // State
        const val UNKNOWN = "unknown"
        const val FAILED = "failed"
        const val CANCELED = "canceled"
        const val ANSWERED = "answered"
        const val REDIRECTED = "redirected"
        const val UNAUTHORIZED = "unauthorized"

        // Metric
        const val TRANSACTIONS = PREFIX + "_transactions"
        const val RETRANSMITS = PREFIX + "_retransmits"
        const val ATTEMPTS = PREFIX + "_attempts"
        const val DURATION = PREFIX + "_duration"
        const val TRYING_DELAY = PREFIX + "_trying-delay"
        const val SETUP_TIME = PREFIX + "_setup-time"
        const val ESTABLISH_TIME = PREFIX + "_establish-time"
        const val DISCONNECT_TIME = PREFIX + "_disconnect-time"
        const val ESTABLISHED = PREFIX + "_established"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 1000
    private var aggregationTimeout: Long = 60000
    private var terminationTimeout: Long = 2000
    private var durationTimeout: Long = 3600000
    private var durationDistributions = TreeMap<Long, String>()
    private var excludedAttributes = emptyList<String>()
    private var correlationRole: String? = null
    private var recordIpAddressesAttributes = false
    private var recordCallUsersAttributes = false

    private var activeSessions = mutableMapOf<String, MutableMap<String, SipSession>>()

    private lateinit var udfExecutor: UdfExecutor
    private lateinit var attributesRegistry: AttributesRegistry

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("call")?.let { config ->
            config.getLong("trim-to-size-delay")?.let {
                trimToSizeDelay = it
            }
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
            config.getJsonArray("duration-distributions")?.forEach {
                durationDistributions[DurationUtil.parseDuration(it as String).toMillis()] = it
            }
            config.getJsonArray("excluded-attributes")?.let {
                excludedAttributes = it.map(Any::toString)
            }
            config.getJsonObject("correlation")?.getString("role")?.let {
                correlationRole = it
            }
        }
        config().getJsonObject("attributes")?.let { config ->
            config.getBoolean("record-ip-addresses")?.let {
                recordIpAddressesAttributes = it
            }
            config.getBoolean("record-call-users")?.let {
                recordCallUsersAttributes = it
            }
        }

        udfExecutor = UdfExecutor(vertx)
        attributesRegistry = AttributesRegistry(vertx, config())

        vertx.setPeriodic(trimToSizeDelay) {
            activeSessions = MutableMapUtil.mutableMapOf(activeSessions)
        }
        vertx.setPeriodic(expirationDelay) {
            terminateExpiredCallSessions()
        }

        GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
            val index = vertx.sharedData().getLocalCounter(PREFIX).await()
            vertx.eventBus().localConsumer<SipTransaction>(PREFIX + "_${index.andIncrement.await()}") { event ->
                try {
                    val transaction = event.body()
                    handle(transaction)
                } catch (e: Exception) {
                    logger.error("SipCallHandler 'handle()' failed.", e)
                }
            }
        }
    }

    open fun handle(transaction: SipTransaction) {
        when (transaction.cseqMethod) {
            "INVITE" -> terminateInviteTransaction(transaction)
            "BYE" -> terminateByeTransaction(transaction)
        }
    }

    open fun terminateInviteTransaction(transaction: SipTransaction) {
        val session = activeSessions.get(transaction.callId)?.get(transaction.legId) ?: SipSession()
        val previousState = session.state

        session.addInviteTransaction(transaction)

        when (session.state) {
            REDIRECTED, CANCELED, FAILED -> {
                terminateCallSession(session)
                activeSessions.get(transaction.callId)?.remove(transaction.legId)
            }
            ANSWERED -> {
                if (previousState != ANSWERED) {
                    writeAttributes(session)
                    writeToDatabase(PREFIX, session, upsert = true)
                    sendToCorrelationHandlerIfNeeded(session)
                }
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

        val attributes = excludeSessionAttributes(transaction.attributes).apply {
            transaction.srcAddr.host?.let { put("src_host", it) }
            transaction.dstAddr.host?.let { put("dst_host", it) }
        }

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
        if (transaction.state == SipTransaction.SUCCEED) {
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
                    if ((session.caller == transaction.caller && session.callee == transaction.callee)
                        || (session.caller == transaction.callee && session.callee == transaction.caller)
                    ) {
                        session.addByeTransaction(transaction)
                    }
                }
            }
        }

        calculateByeTransactionMetrics(transaction)
    }

    open fun calculateByeTransactionMetrics(transaction: SipTransaction) {
        val createdAt = transaction.createdAt

        val attributes = excludeSessionAttributes(transaction.attributes).apply {
            transaction.srcAddr.host?.let { put("src_host", it) }
            transaction.dstAddr.host?.let { put("dst_host", it) }
        }

        transaction.terminatedAt?.let { terminatedAt ->
            if (createdAt < terminatedAt) {
                Metrics.timer(DISCONNECT_TIME, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }
    }

    open fun terminateExpiredCallSessions() {
        val now = System.currentTimeMillis()

        activeSessions.filter { (_, sessions) ->
            sessions.filterValues { session ->
                val expiresAt = if (session.state == UNKNOWN) {
                    session.createdAt + terminationTimeout
                } else {
                    session.terminatedAt?.let { it + terminationTimeout }
                        ?: session.answeredAt?.let { it + durationTimeout }
                        ?: session.createdAt + aggregationTimeout
                }
                val isExpired = expiresAt < now

                if (!isExpired && session.state == ANSWERED) {
                    val attributes = excludeSessionAttributes(session.attributes).apply {
                        session.srcAddr.host?.let { put("src_host", it) }
                        session.dstAddr.host?.let { put("dst_host", it) }
                    }

                    Metrics.counter(ESTABLISHED, attributes).increment()
                }

                return@filterValues isExpired
            }.forEach { (sid, session) ->
                sessions.remove(sid)
                terminateCallSession(session)
            }

            return@filter sessions.isEmpty()
        }.forEach { (callId, _) ->
            activeSessions.remove(callId)
        }
    }

    open fun terminateCallSession(session: SipSession) {
        if (session.terminatedAt == null) {
            session.terminatedAt = System.currentTimeMillis()
        }

        val state = session.state
        if (state == ANSWERED && session.duration == null) {
            session.attributes[Attributes.expired] = true
        }

        udfExecutor.execute(RoutesCE.sip_call_udf,
            // Prepare UDF payload
            mappingFunction = {
                mutableMapOf<String, Any>().apply {
                    val src = session.srcAddr
                    put("src_addr", src.addr)
                    put("src_port", src.port)
                    src.host?.let { put("src_host", it) }

                    val dst = session.dstAddr
                    put("dst_addr", dst.addr)
                    put("dst_port", dst.port)
                    dst.host?.let { put("dst_host", it) }

                    put("payload", mutableMapOf<String, Any>().apply {
                        put("created_at", session.createdAt)
                        put("terminated_at", session.terminatedAt!!)

                        put("state", session.state)
                        put("caller", session.caller)
                        put("callee", session.callee)
                        put("call_id", session.callId)

                        session.duration?.let { put("duration", it) }
                        session.tryingDelay?.let { put("trying_delay", it) }
                        session.setupTime?.let { put("setup_time", it) }
                        session.establishTime?.let { put("establish_time", it) }
                        session.cancelTime?.let { put("cancel_time", it) }
                        session.disconnectTime?.let { put("disconnect_time", it) }
                        session.terminatedBy?.let { put("terminated_by", it) }

                        session.errorCode?.let { put("error_code", it) }
                        session.errorType?.let { put("error_type", it) }

                        put("transactions", session.transactions)
                        put("retransmits", session.retransmits)

                        session.attributes.forEach { (k, v) -> put(k, v) }
                    })
                }
            },
            // Handle UDF result
            completionHandler = { asr ->
                val (_, attributes) = asr.result()

                attributes.forEach { (k, v) -> session.attributes[k] = v }

                writeAttributes(session)
                writeToDatabase(PREFIX, session, upsert = (session.state == ANSWERED))
                sendToCorrelationHandlerIfNeeded(session)
                calculateCallSessionMetrics(session)
            }
        )
    }

    open fun calculateCallSessionMetrics(session: SipSession) {
        val attributes = excludeSessionAttributes(session.attributes).apply {
            put(Attributes.state, session.state)
            session.srcAddr.host?.let { put("src_host", it) }
            session.dstAddr.host?.let { put("dst_host", it) }
        }

        Metrics.counter(TRANSACTIONS, attributes).increment(session.transactions.toDouble())
        Metrics.counter(RETRANSMITS, attributes).increment(session.retransmits.toDouble())

        Metrics.counter(ATTEMPTS, attributes).increment()

        session.duration?.let { duration ->
            durationDistributions.ceilingKey(duration)
                ?.let { attributes[Attributes.distribution] = durationDistributions[it]!! }

            Metrics.summary(DURATION, attributes).record(duration.toDouble())
        }
    }

    open fun writeAttributes(session: SipSession) {
        val attributes = session.attributes
            .toMutableMap()
            .apply {
                put(Attributes.state, session.state)

                val src = session.srcAddr
                put(Attributes.src_addr, if (recordIpAddressesAttributes) src.addr else "")
                src.host?.let { put(Attributes.src_host, it) }

                val dst = session.dstAddr
                put(Attributes.dst_addr, if (recordIpAddressesAttributes) dst.addr else "")
                dst.host?.let { put(Attributes.dst_host, it) }

                val caller = get(Attributes.caller) ?: session.caller
                put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                val callee = get(Attributes.callee) ?: session.callee
                put(Attributes.callee, if (recordCallUsersAttributes) callee else "")

                put(Attributes.call_id, "")

                session.duration?.let { put(Attributes.duration, it) }
                session.tryingDelay?.let { put(Attributes.trying_delay, it) }
                session.setupTime?.let { put(Attributes.setup_time, it) }
                session.establishTime?.let { put(Attributes.establish_time, it) }
                session.cancelTime?.let { put(Attributes.cancel_time, it) }
                session.disconnectTime?.let { put(Attributes.disconnect_time, it) }
                session.terminatedBy?.let { put(Attributes.terminated_by, it) }

                session.errorCode?.let { put(Attributes.error_code, it) }
                session.errorType?.let { put(Attributes.error_type, it) }

                put(Attributes.transactions, session.transactions)
                put(Attributes.retransmits, session.retransmits)

                remove(Attributes.x_call_id)
                remove(Attributes.recording_mode)
            }

        attributesRegistry.handle("sip", attributes)
    }

    open fun sendToCorrelationHandlerIfNeeded(session: SipSession) {
        if (correlationRole == null) return

        val correlationEvent = JsonObject().apply {
            put("created_at", session.createdAt)
            session.terminatedAt?.let { put("terminated_at", it) }

            val src = session.srcAddr
            put("src_host", src.host ?: src.addr)

            val dst = session.dstAddr
            put("dst_host", dst.host ?: dst.addr)

            put("state", session.state)

            put("caller", session.attributes[Attributes.caller] ?: session.caller)
            put("callee", session.attributes[Attributes.callee] ?: session.callee)

            put("call_id", session.callId)
            session.attributes[Attributes.x_call_id]?.let { put("x_call_id", it) }
        }

        when (correlationRole) {
            "aggregator" -> vertx.eventBus().localSend(RoutesCE.sip + "_call_correlation", correlationEvent)
            "reporter" -> vertx.eventBus().send(RoutesCE.sip + "_call_correlation", correlationEvent)
        }
    }

    open fun writeToDatabase(prefix: String, session: SipSession, upsert: Boolean = false) {
        val collection = prefix + "_index_" + timeSuffix.format(session.createdAt)

        val operation = JsonObject().apply {
            if (upsert) {
                put("type", "UPDATE")
                put("upsert", true)
                put("filter", JsonObject().apply {
                    put("created_at", session.createdAt)
                    val src = session.srcAddr
                    src.host?.let { put("src_host", it) } ?: put("src_addr", src.addr)
                    val dst = session.dstAddr
                    dst.host?.let { put("dst_host", it) } ?: put("dst_addr", dst.addr)
                    put("call_id", session.callId)
                })
            }
            put("document", JsonObject().apply {
                var document = this

                val src = session.srcAddr
                val dst = session.dstAddr

                if (upsert) {
                    document = JsonObject()
                    put("\$setOnInsert", document)
                }
                document.apply {
                    put("created_at", session.createdAt)
                    put("src_addr", src.addr)
                    put("src_port", src.port)
                    put("dst_addr", dst.addr)
                    put("dst_port", dst.port)
                    put("call_id", session.callId)
                    session.attributes[Attributes.x_call_id]?.let { put("x_call_id", it) }
                    put("caller", session.attributes[Attributes.caller] ?: session.caller)
                    put("callee", session.attributes[Attributes.callee] ?: session.callee)
                }

                if (upsert) {
                    document = JsonObject()
                    put("\$set", document)
                }
                document.apply {
                    put("state", session.state)

                    session.terminatedAt?.let { put("terminated_at", it) }

                    session.srcAddr.host?.let { put("src_host", it) }
                    session.dstAddr.host?.let { put("dst_host", it) }

                    session.duration?.let { put("duration", it) }
                    session.tryingDelay?.let { put("trying_delay", it) }
                    session.setupTime?.let { put("setup_time", it) }
                    session.establishTime?.let { put("establish_time", it) }
                    session.cancelTime?.let { put("cancel_time", it) }
                    session.disconnectTime?.let { put("disconnect_time", it) }
                    session.terminatedBy?.let { put("terminated_by", it) }

                    session.errorCode?.let { put("error_code", it) }
                    session.errorType?.let { put("error_type", it) }

                    put("transactions", session.transactions)
                    put("retransmits", session.retransmits)

                    excludeSessionAttributes(session.attributes).forEach { (name, value) -> put(name, value) }
                }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    private fun excludeSessionAttributes(attributes: Map<String, Any>): MutableMap<String, Any> {
        return attributes.toMutableMap().apply {
            remove(Attributes.caller)
            remove(Attributes.callee)
            remove(Attributes.x_call_id)
            remove(Attributes.recording_mode)
            excludedAttributes.forEach { remove(it) }
        }
    }

    inner class SipSession {

        var state = UNKNOWN

        var createdAt: Long = 0
        var answeredAt: Long? = null
        var terminatedAt: Long? = null

        lateinit var srcAddr: Address
        lateinit var dstAddr: Address

        lateinit var callId: String
        lateinit var callee: String
        lateinit var caller: String

        var duration: Long? = null
        var tryingDelay: Long? = null
        var setupTime: Long? = null
        var establishTime: Long? = null
        var cancelTime: Long? = null
        var disconnectTime: Long? = null
        var terminatedBy: String? = null

        var errorCode: Int? = null
        var errorType: String? = null

        var transactions = 0
        var retransmits = 0

        var attributes = mutableMapOf<String, Any>()

        fun addInviteTransaction(transaction: SipTransaction) {
            transactions++
            retransmits += transaction.retransmits

            if (createdAt == 0L) {
                createdAt = transaction.createdAt
                srcAddr = transaction.srcAddr
                dstAddr = transaction.dstAddr
                callId = transaction.callId
                callee = transaction.callee
                caller = transaction.caller
            }

            if (state != ANSWERED) {
                transaction.tryingAt?.let { tryingAt ->
                    tryingDelay = tryingAt - createdAt
                }

                when (transaction.state) {
                    SipTransaction.SUCCEED -> {
                        state = ANSWERED
                        answeredAt = transaction.terminatedAt ?: transaction.createdAt
                        transaction.ringingAt?.let { ringingAt ->
                            setupTime = ringingAt - createdAt
                        }
                        transaction.terminatedAt?.let { terminatedAt ->
                            establishTime = terminatedAt - createdAt
                        }
                    }
                    SipTransaction.REDIRECTED -> {
                        state = REDIRECTED
                        terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                    }
                    SipTransaction.UNAUTHORIZED -> {
                        state = UNAUTHORIZED
                    }
                    SipTransaction.CANCELED -> {
                        state = CANCELED
                        (transaction.terminatedAt ?: transaction.createdAt).let { terminatedAt ->
                            this.terminatedAt = terminatedAt
                            transaction.ringingAt?.let { ringingAt ->
                                if (terminatedAt > ringingAt) {
                                    cancelTime = terminatedAt - ringingAt
                                }
                            }
                        }
                    }
                    SipTransaction.FAILED -> {
                        state = FAILED
                        terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                    }
                }
            }

            errorCode = transaction.errorCode
            errorType = transaction.errorType

            transaction.attributes.forEach { (name, value) -> attributes[name] = value }
        }

        fun addByeTransaction(transaction: SipTransaction) {
            transactions++
            retransmits += transaction.retransmits

            if (terminatedAt == null) {
                terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                terminatedAt?.let {
                    disconnectTime = it - transaction.createdAt
                }

                answeredAt?.let { answeredAt ->
                    duration = transaction.createdAt - answeredAt
                }

                terminatedBy = if (caller == transaction.caller) "caller" else "callee"
            }

            errorCode = transaction.errorCode
            errorType = transaction.errorType

            transaction.attributes.forEach { (name, value) -> attributes[name] = value }
        }
    }
}
