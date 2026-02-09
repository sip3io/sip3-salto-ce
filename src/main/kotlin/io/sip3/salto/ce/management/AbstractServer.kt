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

package io.sip3.salto.ce.management

import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.net.URI

/**
 * Abstract Management Server
 */
abstract class AbstractServer : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    lateinit var name: String

    override fun start() {
        name = config().getString("name")

        readConfig()

        vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>(RoutesCE.management + "_send") { event ->
            try {
                val (message, uris) = event.body()
                send(message, uris)
            } catch (e: Exception) {
                logger.error(e) { "AbstractServer 'send()' failed." }
            }
        }

        startServer()
    }

    abstract fun readConfig()

    abstract fun startServer()

    abstract fun send(message: JsonObject, uris: List<URI>)

    open fun handle(uri: URI, message: JsonObject) {
        logger.trace { "Handle message from $uri: $message" }
        vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, message)) { event ->
            event.result()?.body()?.let { response ->
                logger.trace { "Send response to $uri: $response" }
                send(response, listOf(uri))
            }
        }
    }
}