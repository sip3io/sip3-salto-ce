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

package io.sip3.salto.ce

import io.sip3.commons.vertx.test.VertxTest
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BootstrapTest : VertxTest() {

    companion object {
        const val UDF_LOCATION = "src/test/resources/udf"
    }

    init {
        System.setProperty("udf.location", UDF_LOCATION)
    }

    @Test
    fun `Deploy and test Groovy UDF`() {
        val message = "Groovy is awesome"
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Bootstrap::class, JsonObject().apply {
                        put("server", JsonObject().apply {
                            put("uri", "udp://0.0.0.0:15062")
                        })
                    })
                },
                execute = {
                    vertx.setPeriodic(100) {
                        vertx.eventBus().send("groovy", message)
                    }
                },
                assert = {
                    vertx.eventBus().localConsumer<String>("kotlin") { event ->
                        context.verify {
                            assertEquals(message, event.body())
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Deploy and test JavaScript UDF`() {
        val message = "JavaScript is awesome"
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Bootstrap::class, JsonObject().apply {
                        put("server", JsonObject().apply {
                            put("uri", "udp://0.0.0.0:15063")
                        })
                    })
                },
                execute = {
                    vertx.setPeriodic(100) {
                        vertx.eventBus().send("js", message)
                    }
                },
                assert = {
                    vertx.eventBus().localConsumer<String>("kotlin") { event ->
                        context.verify {
                            assertEquals(message, event.body())
                        }
                        context.completeNow()
                    }
                }
        )
    }
}