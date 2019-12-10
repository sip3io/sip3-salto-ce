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
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.domain.SipTransaction
import io.sip3.salto.ce.util.cseqMethod
import io.sip3.salto.ce.util.transactionId
import io.vertx.core.AbstractVerticle
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Handles SIP calls
 */
open class SipCallHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "sip_call"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var expirationDelay: Long = 1000
    private var aggregationTimeout: Long = 60000
    private var terminationTimeout: Long = 10000
    private var durationTimeout: Long = 3600000

    private val inviteTransactions = mutableMapOf<String, SipTransaction>()
    private val byeTransactions = mutableMapOf<String, SipTransaction>()

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
            }
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
        val transactionId = message.transactionId()

        val cseqMethod = message.cseqMethod()
        when (cseqMethod) {
            "INVITE" -> {
                val transaction = inviteTransactions.getOrPut(transactionId) { SipTransaction() }
                transaction.addMessage(packet, message)
                // TODO...
            }
            "ACK" -> {
                // To simplify call aggregation we decided to skip ACK transaction.
                // Moreover, skipped ACK transaction will not affect call aggregation result.
            }
            "BYE" -> {
                val transaction = byeTransactions.getOrPut(transactionId) { SipTransaction() }
                transaction.addMessage(packet, message)
                // TODO...
            }
        }
    }
}