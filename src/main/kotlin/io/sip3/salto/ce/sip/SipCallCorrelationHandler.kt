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

import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Handles SIP call events to correlate entire SIP call
 */
@Instance(singleton = true)
@ConditionalOnProperty(pointer = "/sip/call/correlation/role", matcher = "aggregator")
open class SipCallCorrelationHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 1000

    private var sessionsByUsers = mutableMapOf<String, MutableList<JsonObject>>()
    private var sessionsByCallIds = mutableMapOf<String, MutableList<JsonObject>>()
    private var sessionsByXCallIds = mutableMapOf<String, MutableList<JsonObject>>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("call")?.getJsonObject("correlation")?.let { config ->
            config.getLong("trim-to-size-delay")?.let {
                trimToSizeDelay = it
            }
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
        }

        vertx.setPeriodic(trimToSizeDelay) {
            sessionsByUsers = MutableMapUtil.mutableMapOf(sessionsByUsers)
            sessionsByCallIds = MutableMapUtil.mutableMapOf(sessionsByCallIds)
            sessionsByXCallIds = MutableMapUtil.mutableMapOf(sessionsByXCallIds)
        }
        vertx.setPeriodic(expirationDelay) {
            terminateExpiredCallSessions()
        }

        vertx.eventBus().consumer<JsonObject>(RoutesCE.sip + "_call_correlation") { event ->
            try {
                handle(event.body())
            } catch (e: Exception) {
                logger.error("SipCallCorrelationHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(callEvent: JsonObject) {
        // TODO...
    }

    open fun terminateExpiredCallSessions() {
        // TODO...
    }
}