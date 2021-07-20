/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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

import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging

/**
 * Handles SIP call events to correlate entire SIP call
 */
@Instance(singleton = true)
@ConditionalOnProperty(pointer = "/sip/call/correlation/role", matcher = "aggregator")
open class SipCallCorrelationHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    override fun start() {
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
}