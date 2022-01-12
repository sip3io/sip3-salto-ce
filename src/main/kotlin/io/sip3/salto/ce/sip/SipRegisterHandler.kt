/*
 * Copyright 2018-2022 SIP3.IO, Corp.
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
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.AttributeValue
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Handles SIP registrations
 */
@Instance
open class SipRegisterHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        // Prefix
        const val PREFIX = "sip_register"

        // State
        const val UNKNOWN = "unknown"
        const val REDIRECTED = "redirected"
        const val FAILED = "failed"
        const val REGISTERED = "registered"
        const val UNAUTHORIZED = "unauthorized"

        // Metric
        const val REQUEST_DELAY = PREFIX + "_request-delay"
        const val REMOVED = PREFIX + "_removed"
        const val ACTIVE = PREFIX + "_active"
        const val OVERLAPPED_INTERVAL = PREFIX + "_overlapped-interval"
        const val OVERLAPPED_FRACTION = PREFIX + "_overlapped-fraction"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var expirationDelay: Long = 1000
    private var aggregationTimeout: Long = 10000
    private var updatePeriod: Long = 60000
    private var durationTimeout: Long = 900000
    private var recordIpAddressesAttributes = false
    private var recordCallUsersAttributes = false

    private lateinit var activeRegistrations: PeriodicallyExpiringHashMap<String, SipRegistration>
    private lateinit var activeSessions: PeriodicallyExpiringHashMap<String, SipSession>
    private lateinit var activeSessionCounters: PeriodicallyExpiringHashMap<String, AtomicInteger>

    private lateinit var attributesRegistry: AttributesRegistry

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("register")?.let { config ->
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
            config.getLong("update-period")?.let {
                updatePeriod = it
            }
            config.getLong("duration-timeout")?.let {
                durationTimeout = it
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

        attributesRegistry = AttributesRegistry(vertx, config())

        activeRegistrations = PeriodicallyExpiringHashMap.Builder<String, SipRegistration>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, registration -> registration.createdAt + aggregationTimeout }
            .onExpire { _, registration -> terminateRegistration(registration) }
            .build(vertx)

        activeSessions = PeriodicallyExpiringHashMap.Builder<String, SipSession>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, session -> terminateSessionAt(session) }
            .onRemain { now, _, session -> updateSession(now, session) }
            .onExpire { _, session -> terminateSession(session) }
            .build(vertx)

        activeSessionCounters = PeriodicallyExpiringHashMap.Builder<String, AtomicInteger>()
            .delay(expirationDelay)
            .expireAt { _, counter -> if (counter.get() > 0) Long.MAX_VALUE else Long.MIN_VALUE }
            .onRemain { hostsKey, counter -> calculateActiveSessions(hostsKey, counter) }
            .build(vertx)

        GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
            val index = vertx.sharedData().getLocalCounter(PREFIX).await()
            vertx.eventBus().localConsumer<SipTransaction>(PREFIX + "_${index.andIncrement.await()}") { event ->
                try {
                    val transaction = event.body()
                    handle(transaction)
                } catch (e: Exception) {
                    logger.error("SipRegisterHandler 'handle()' failed.", e)
                }
            }
        }
    }

    open fun handle(transaction: SipTransaction) {
        val id = "${transaction.legId}:${transaction.callId}"

        val registration = activeRegistrations.get(id) ?: SipRegistration()
        registration.addRegisterTransaction(transaction)

        when (registration.state) {
            UNKNOWN, REDIRECTED, FAILED -> {
                terminateRegistration(registration)
                activeRegistrations.remove(id)
            }
            REGISTERED -> {
                calculateRegistrationMetrics(registration)
                val session = activeSessions.getOrPut(id) { SipSession() }
                session.addSipRegistration(registration)
                activeRegistrations.remove(id)

                val activeSessionCountersKey = "${session.srcAddr.host ?: ""}:${session.dstAddr.host ?: ""}"
                activeSessionCounters.getOrPut(activeSessionCountersKey) { AtomicInteger(0) }.incrementAndGet()

                session.overlappedInterval?.let { interval ->
                    val attributes = excludeRegistrationAttributes(registration.attributes)
                        .filterValues { v -> v.mode.metrics }
                        .mapValues { (_, v) -> v.value }
                        .toMutableMap()
                        .apply {
                            registration.srcAddr.host?.let { put("src_host", it) }
                            registration.dstAddr.host?.let { put("dst_host", it) }
                        }

                    Metrics.timer(OVERLAPPED_INTERVAL, attributes).record(interval, TimeUnit.MILLISECONDS)
                    session.overlappedFraction?.let {
                        Metrics.summary(OVERLAPPED_FRACTION, attributes).record(it)
                    }
                }
            }
            else -> {
                activeRegistrations.put(id, registration)
            }
        }
    }

    open fun calculateRegistrationMetrics(registration: SipRegistration) {
        val createdAt = registration.createdAt

        val attributes = excludeRegistrationAttributes(registration.attributes)
            .filterValues { v -> v.mode.metrics }
            .mapValues { (_, v) -> v.value }
            .toMutableMap()
            .apply {
                registration.srcAddr.host?.let { put("src_host", it) }
                registration.dstAddr.host?.let { put("dst_host", it) }
            }

        registration.terminatedAt?.let { terminatedAt ->
            if (createdAt < terminatedAt) {
                Metrics.timer(REQUEST_DELAY, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
            }

            registration.expiresAt?.let { expiresAt ->
                if (expiresAt == terminatedAt) {
                    Metrics.counter(REMOVED, attributes).increment()
                }
            }
        }
    }

    open fun terminateRegistration(registration: SipRegistration) {
        if (registration.terminatedAt == null) {
            registration.terminatedAt = System.currentTimeMillis()
        }

        writeAttributes(registration)
        writeToDatabase(PREFIX, registration, false)
    }

    open fun terminateSessionAt(session: SipSession): Long {
        var expireAt = (session.expiresAt ?: session.terminatedAt ?: session.createdAt) + aggregationTimeout

        if (expireAt > session.createdAt + durationTimeout) {
            session.terminatedAt = expireAt - aggregationTimeout
            expireAt = session.createdAt + durationTimeout
        }

        return expireAt
    }

    private fun updateSession(now: Long, session: SipSession) {
        val updatedAt = session.updatedAt

        if (!session.synced && (updatedAt == null || updatedAt + updatePeriod < now)) {
            session.updatedAt = now
            writeAttributes(session)
            writeToDatabase(PREFIX, session, updatedAt != null)
            session.synced = true
        }
    }

    private fun terminateSession(session: SipSession) {
        if (session.terminatedAt == null) {
            session.terminatedAt = System.currentTimeMillis()
        }

        session.duration = session.terminatedAt!! - session.createdAt

        writeAttributes(session)
        writeToDatabase(PREFIX, session, true)

        val activeSessionCountersKey = "${session.srcAddr.host ?: ""}:${session.dstAddr.host ?: ""}"
        activeSessionCounters.get(activeSessionCountersKey)?.decrementAndGet()
    }

    open fun calculateActiveSessions(hostsKey: String, counter: AtomicInteger) {
        val hosts = hostsKey.split(":")

        val attributes = mutableMapOf<String, String>().apply {
            if (hosts[0].isNotBlank()) put("src_host", hosts[0])
            if (hosts[1].isNotBlank()) put("dst_host", hosts[1])
        }

        Metrics.counter(ACTIVE, attributes).increment(counter.toDouble())
    }

    open fun writeAttributes(registration: SipRegistration) {
        val attributes = registration.attributes
            .filterValues { v -> v.mode.db }
            .mapValues { (_, v) -> if (!v.mode.options && v.value is String) "" else v.value }
            .toMutableMap()
            .apply {
                put(Attributes.method, "REGISTER")
                put(Attributes.state, registration.state)

                val src = registration.srcAddr
                put(Attributes.src_addr, if (recordIpAddressesAttributes) src.addr else "")
                src.host?.let { put(Attributes.src_host, it) }

                val dst = registration.dstAddr
                put(Attributes.dst_addr, if (recordIpAddressesAttributes) dst.addr else "")
                dst.host?.let { put(Attributes.dst_host, it) }

                val caller = get(Attributes.caller) ?: registration.caller
                put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                val callee = get(Attributes.callee) ?: registration.callee
                put(Attributes.callee, if (recordCallUsersAttributes) callee else "")

                put(Attributes.call_id, "")

                put(Attributes.transactions, registration.transactions)
                put(Attributes.retransmits, registration.retransmits)

                (registration as? SipSession)?.let { session ->
                    session.overlappedInterval?.let { put(Attributes.overlapped_interval, it) }
                    session.overlappedFraction?.let { put(Attributes.overlapped_fraction, it) }
                }

                remove(Attributes.x_call_id)
                remove(Attributes.recording_mode)
            }

        attributesRegistry.handle("sip", attributes)
    }

    open fun writeToDatabase(prefix: String, registration: SipRegistration, upsert: Boolean = false) {
        val collection = prefix + "_index_" + timeSuffix.format(registration.createdAt)

        val operation = JsonObject().apply {
            if (upsert) {
                put("type", "UPDATE")
                put("upsert", true)
                put("filter", JsonObject().apply {
                    put("created_at", registration.createdAt)
                    val src = registration.srcAddr
                    src.host?.let { put("src_host", it) } ?: put("src_addr", src.addr)
                    val dst = registration.dstAddr
                    dst.host?.let { put("dst_host", it) } ?: put("dst_addr", dst.addr)
                    put("call_id", registration.callId)
                })
            }
            put("document", JsonObject().apply {
                var document = this

                val src = registration.srcAddr
                val dst = registration.dstAddr

                if (upsert) {
                    document = JsonObject()
                    put("\$setOnInsert", document)
                }
                document.apply {
                    put("created_at", registration.createdAt)
                    put("src_addr", src.addr)
                    put("src_port", src.port)
                    put("dst_addr", dst.addr)
                    put("dst_port", dst.port)
                    put("call_id", registration.callId)
                }

                if (upsert) {
                    document = JsonObject()
                    put("\$set", document)
                }
                document.apply {
                    put("state", registration.state)

                    (registration.terminatedAt ?: registration.expiresAt)?.let { put("terminated_at", it) }

                    registration.srcAddr.host?.let { put("src_host", it) }
                    registration.dstAddr.host?.let { put("dst_host", it) }

                    put("caller", registration.caller)
                    put("callee", registration.callee)

                    registration.errorCode?.let { put("error_code", it) }
                    registration.errorType?.let { put("error_type", it) }

                    put("transactions", registration.transactions)
                    put("retransmits", registration.retransmits)

                    registration.attributes
                        .filterValues { v -> v.mode.db }
                        .forEach { (name, value) -> put(name, value) }
                }

                if (registration is SipSession) {
                    registration.duration?.let { document.put("duration", it) }

                    registration.overlappedInterval?.let { document.put("overlapped_interval", it) }
                    registration.overlappedFraction?.let { document.put("overlapped_fraction", it) }

                    var registrations: Any = registration.registrations
                        .map { (createdAt, terminatedAt) ->
                            JsonObject().apply {
                                put("created_at", createdAt)
                                put("terminated_at", terminatedAt)
                            }
                        }
                    registration.registrations.clear()

                    if (upsert) {
                        document = JsonObject()
                        put("\$push", document)

                        // Wrap registrations list
                        registrations = JsonObject().apply { put("\$each", registrations) }
                    }
                    document.put("registrations", registrations)
                }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    private fun excludeRegistrationAttributes(attributes: Map<String, AttributeValue>): MutableMap<String, AttributeValue> {
        return attributes.toMutableMap().apply {
            remove(Attributes.caller)
            remove(Attributes.callee)
            remove(Attributes.x_call_id)
            remove(Attributes.recording_mode)
            remove(Attributes.debug)
        }
    }

    inner class SipSession : SipRegistration() {

        var updatedAt: Long? = null
        var duration: Long? = null
        var overlappedInterval: Long? = null
        var overlappedFraction: Double? = null

        var synced: Boolean = false

        val registrations = mutableListOf<Pair<Long, Long>>()

        fun addSipRegistration(registration: SipRegistration) {
            transactions += registration.transactions
            retransmits += registration.retransmits

            if (createdAt == 0L) {
                createdAt = registration.createdAt
                srcAddr = registration.srcAddr
                dstAddr = registration.dstAddr
                callId = registration.callId
                callee = registration.callee
                caller = registration.caller
                state = registration.state
            } else {
                synced = false
                overlappedInterval = expiresAt!! - registration.createdAt

                if (registration.expires > 0L) {
                    val fraction = overlappedInterval!! / expires.toDouble()
                    if (fraction > (overlappedFraction ?: 0.0)) {
                        overlappedFraction = fraction
                    }
                }
            }

            expiresAt = registration.expiresAt
            expires = registration.expires

            registrations.add(Pair(registration.createdAt, registration.expiresAt ?: registration.terminatedAt ?: registration.createdAt))

            registration.attributes.forEach { (name, value) -> attributes[name] = value }
        }
    }

    open inner class SipRegistration {

        var state = UNKNOWN

        var createdAt: Long = 0
        var expiresAt: Long? = null
        var terminatedAt: Long? = null

        lateinit var srcAddr: Address
        lateinit var dstAddr: Address

        lateinit var callId: String
        lateinit var callee: String
        lateinit var caller: String

        var errorCode: String? = null
        var errorType: String? = null

        var transactions = 0
        var retransmits = 0

        var expires: Long = 0L

        var attributes = mutableMapOf<String, AttributeValue>()

        fun addRegisterTransaction(transaction: SipTransaction) {
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

            transaction.expires?.let { expires ->
                if (expires > 0) {
                    this.expires = expires * 1000L
                    expiresAt = transaction.createdAt + this.expires
                } else {
                    // Registration has to be removed
                    expiresAt = transaction.terminatedAt ?: transaction.createdAt
                }
            }

            // Update state
            if (state != REGISTERED) {
                state = when (transaction.state) {
                    SipTransaction.SUCCEED -> REGISTERED
                    SipTransaction.REDIRECTED -> REDIRECTED
                    SipTransaction.UNAUTHORIZED -> UNAUTHORIZED
                    SipTransaction.FAILED -> FAILED
                    else -> UNKNOWN
                }
            }

            if (state != UNAUTHORIZED) {
                terminatedAt = transaction.terminatedAt ?: transaction.createdAt
            }

            errorCode = transaction.errorCode
            errorType = transaction.errorType

            transaction.attributes.forEach { (name, value) -> attributes[name] = value }
        }
    }
}
