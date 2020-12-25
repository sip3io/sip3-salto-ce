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

import io.sip3.commons.domain.Codec
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.setPeriodic
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootstrapTest : VertxTest() {

    companion object {

        const val UDF_LOCATION = "src/test/resources/udf/BootstrapTest"
        const val CODEC_LOCATION = "src/test/resources/codecs.yml"
    }

    init {
        System.setProperty("udf.location", UDF_LOCATION)
        System.setProperty("codecs.location", CODEC_LOCATION)
    }

    @Test
    fun `Deploy and test Groovy UDF`() {
        val message = "Groovy is awesome"
        runTest(
            deploy = {
                vertx.deployTestVerticle(Bootstrap::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "udp://0.0.0.0:${findRandomPort()}")
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
                        put("uri", "udp://0.0.0.0:${findRandomPort()}")
                    })
                })
            },
            execute = {
                vertx.setPeriodic(500, 100) {
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

    @Test
    fun `Codec directory read`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(Bootstrap::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "udp://0.0.0.0:${findRandomPort()}")
                    })
                })
            },
            assert = {
                vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
                    context.verify {
                        val config = event.body()
                        assertTrue(config.containsKey("codecs"))

                        val codecs = config.getJsonArray("codecs")
                        assertEquals(1, codecs.size())

                        val codec = (codecs.first() as JsonObject).mapTo(Codec::class.java)
                        assertEquals("PCMA", codec.name)
                        assertEquals(0x08, codec.payloadTypes.first())
                        assertEquals(8000, codec.clockRate)
                        assertEquals(0.0F, codec.ie)
                        assertEquals(4.3F, codec.bpl)
                    }

                    context.completeNow()
                }
            }
        )
    }
}