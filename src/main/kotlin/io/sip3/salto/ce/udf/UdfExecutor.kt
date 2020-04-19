/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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

package io.sip3.salto.ce.udf

import io.sip3.commons.vertx.util.endpoints
import io.sip3.commons.vertx.util.setPeriodic
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

/**
 * Executes User-Defined Functions
 */
class UdfExecutor(val vertx: Vertx) {

    private val logger = KotlinLogging.logger {}

    companion object {

        val NO_RESULT_FUTURE: Future<Pair<Boolean, Map<String, Any>>> = Future.succeededFuture(Pair(true, emptyMap()))
    }

    private var checkPeriod: Long = 300000
    private var executionTimeout: Long = 100

    private var endpoints = emptySet<String>()

    init {
        vertx.orCreateContext.config().getJsonObject("udf")?.let { config ->
            config.getLong("check-period")?.let {
                checkPeriod = it
            }
            config.getLong("execution-timeout")?.let {
                executionTimeout = it
            }
        }

        vertx.setPeriodic(0, checkPeriod) {
            endpoints = vertx.eventBus().endpoints()
            logger.debug { "Update UDF endpoints: $endpoints" }
        }
    }

    fun execute(endpoint: String, payload: MutableMap<String, Any>, completionHandler: (AsyncResult<Pair<Boolean, Map<String, Any>>>) -> Unit) {
        if (!endpoints.contains(endpoint)) {
            completionHandler.invoke(NO_RESULT_FUTURE)
            return
        }

        GlobalScope.launch(vertx.dispatcher()) {
            var attributes: Map<String, Any> = mutableMapOf()
            payload["attributes"] = attributes

            logger.debug { "Call '$endpoint' UDF. Payload: $payload" }
            try {
                val result = withTimeout(executionTimeout) {
                    vertx.eventBus().requestAwait<Boolean>(endpoint, payload, USE_LOCAL_CODEC).body()
                }

                when (result) {
                    true -> {
                        attributes = attributes.filter { (k, v) ->
                            when (v) {
                                is String, is Boolean -> true
                                else -> {
                                    logger.warn("UDF attribute $k will be skipped due to unsupported value type.")
                                    return@filter false
                                }
                            }
                        }
                        completionHandler.invoke(Future.succeededFuture(Pair(true, attributes)))
                    }
                    else -> {
                        completionHandler.invoke(Future.succeededFuture(Pair(false, emptyMap())))
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "UdfExecutor 'execute()' failed. Endpoint: $endpoint, payload: ${JsonObject(payload).encodePrettily()}" }
                completionHandler.invoke(NO_RESULT_FUTURE)
            }
        }
    }
}