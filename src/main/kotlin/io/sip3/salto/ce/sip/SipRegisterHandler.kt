package io.sip3.salto.ce.sip

import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.util.expires
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.shareddata.getLocalCounterAwait
import io.vertx.kotlin.core.shareddata.incrementAndGetAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var expirationDelay: Long = 1000
    private var aggregationTimeout: Long = 5000
    private var updatePeriod: Long = 60000
    private var durationTimeout: Long = 3600000
    private var transactionExclusions = emptyList<String>()
    private var recordCallUsersAttributes = false

    private var activeRegistrations = mutableMapOf<String, SipRegistration>()

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
            config.getJsonArray("transaction-exclusions")?.let {
                transactionExclusions = it.map(Any::toString)
            }
        }
        config().getJsonObject("attributes")?.getBoolean("record-call-users")?.let {
            recordCallUsersAttributes = it
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredRegistrations()
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val index = vertx.sharedData().getLocalCounterAwait(PREFIX)
            vertx.eventBus().localConsumer<SipTransaction>(PREFIX + "_${index.incrementAndGetAwait()}") { event ->
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

        var registration = activeRegistrations[id] ?: SipRegistration()
        registration.addRegisterTransaction(transaction)

        when (registration.state) {
            UNKNOWN, REDIRECTED, FAILED -> {
                terminateRegistration(registration)
                activeRegistrations.remove(id)
            }
            else -> {
                activeRegistrations[id] = registration
            }
        }

        calculateRegisterTransactionMetrics(transaction)
    }

    open fun calculateRegisterTransactionMetrics(transaction: SipTransaction) {
        val createdAt = transaction.createdAt

        val attributes = excludeRegistrationAttributes(transaction.attributes)
                .apply {
                    transaction.srcAddr.host?.let { put("src_host", it) }
                    transaction.dstAddr.host?.let { put("dst_host", it) }
                }

        transaction.terminatedAt?.let { terminatedAt ->
            if (createdAt < terminatedAt) {
                Metrics.timer(REQUEST_DELAY, attributes).record(terminatedAt - createdAt, TimeUnit.MILLISECONDS)
            }
        }

        transaction.response?.expires()?.let { expires ->
            if (expires == 0) {
                Metrics.counter(REMOVED, attributes).increment()
            }
        }
    }

    open fun terminateExpiredRegistrations() {
        val now = System.currentTimeMillis()

        activeRegistrations.filterValues { registration ->
            when (registration.state) {
                REGISTERED -> {
                    val expiredAt = registration.expiredAt!!

                    // Check if registration exceeded `durationTimeout`
                    if (registration.createdAt + durationTimeout < now) {
                        registration.terminatedAt = expiredAt

                        return@filterValues true
                    }

                    if (expiredAt >= now) {
                        // Check `updated_at` and write to database if needed
                        val updatedAt = registration.updatedAt
                        if (updatedAt == null || updatedAt + updatePeriod < now) {
                            registration.updatedAt = now
                            writeToDatabase(PREFIX, registration, updatedAt != null)
                        }

                        // Calculate `active` registrations
                        val attributes = excludeRegistrationAttributes(registration.attributes)
                                .apply {
                                    registration.srcAddr.host?.let { put("src_host", it) }
                                    registration.dstAddr.host?.let { put("dst_host", it) }
                                }
                        Metrics.counter(ACTIVE, attributes).increment()

                        return@filterValues false
                    } else {
                        registration.terminatedAt = expiredAt

                        return@filterValues true
                    }
                }
                else -> {
                    return@filterValues registration.createdAt + aggregationTimeout < now
                }
            }
        }.forEach { (id, registration) ->
            activeRegistrations.remove(id)
            terminateRegistration(registration)
        }
    }

    open fun terminateRegistration(registration: SipRegistration) {
        if (registration.terminatedAt == null) {
            registration.terminatedAt = System.currentTimeMillis()
        }

        writeAttributes(registration)
        writeToDatabase(PREFIX, registration, registration.state == REGISTERED)
    }

    open fun writeAttributes(registration: SipRegistration) {
        val attributes = registration.attributes
                .toMutableMap()
                .apply {
                    remove(Attributes.src_host)
                    remove(Attributes.dst_host)

                    put(Attributes.method, "REGISTER")
                    put(Attributes.state, registration.state)

                    put(Attributes.call_id, "")
                    remove(Attributes.x_call_id)

                    val caller = get(Attributes.caller) ?: registration.caller
                    put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                    val callee = get(Attributes.callee) ?: registration.callee
                    put(Attributes.callee, if (recordCallUsersAttributes) callee else "")
                }

        vertx.eventBus().send(RoutesCE.attributes, Pair("sip", attributes), USE_LOCAL_CODEC)
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

                    registration.terminatedAt?.let { put("terminated_at", it) }

                    registration.srcAddr.host?.let { put("src_host", it) }
                    registration.dstAddr.host?.let { put("dst_host", it) }

                    put("caller", registration.caller)
                    put("callee", registration.callee)

                    registration.attributes.forEach { (name, value) -> put(name, value) }
                }
            })
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, operation), USE_LOCAL_CODEC)
    }

    private fun excludeRegistrationAttributes(attributes: Map<String, Any>): MutableMap<String, Any> {
        return attributes.toMutableMap().apply {
            remove(Attributes.caller)
            remove(Attributes.callee)
            remove(Attributes.error_code)
            remove(Attributes.error_type)
            remove(Attributes.x_call_id)
            remove(Attributes.retransmits)
            transactionExclusions.forEach { remove(it) }
        }
    }

    inner class SipRegistration {

        var state = SipCallHandler.UNKNOWN

        var updatedAt: Long? = null

        var createdAt: Long = 0
        var expiredAt: Long? = null
        var terminatedAt: Long? = null

        lateinit var srcAddr: Address
        lateinit var dstAddr: Address

        lateinit var callId: String
        lateinit var callee: String
        lateinit var caller: String

        var attributes = mutableMapOf<String, Any>()

        fun addRegisterTransaction(transaction: SipTransaction) {
            if (createdAt == 0L) {
                createdAt = transaction.createdAt
                srcAddr = transaction.srcAddr
                dstAddr = transaction.dstAddr
                callId = transaction.callId
                callee = transaction.callee
                caller = transaction.caller
            }

            transaction.response?.expires()?.let { expires ->
                if (expires > 0) {

                } else {
                    // Registration has to be removed
                    terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                }
            }
        }
    }
}