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

package io.sip3.salto.ce.router

import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.endpoints
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.MockKSingletonExtension
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.hosts.HostRegistry
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class, MockKSingletonExtension::class)
class RouterTest : VertxTest() {

    companion object {

        const val UDF_GROOVY = "src/test/resources/udf/RouterTest/RouterTest.groovy"
        const val UDF_JS = "src/test/resources/udf/RouterTest/RouterTest.js"
    }

    @Test
    fun `Route SIP packet without host mapping`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(Router::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                val packet = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                vertx.eventBus().localSend(RoutesCE.router, Pair(sender, listOf(packet)))
            },
            assert = {
                vertx.eventBus().consumer<Packet>(RoutesCE.sip) { event ->
                    val packet = event.body()
                    context.verify {
                        assertTrue(packet is Packet)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Route SIP packet with host mapping`() {

        val host = JsonObject().apply {
            put("name", "test")
            put("addr", JsonArray().apply {
                add("29.11.19.88")
            })
        }

        every {
            HostRegistry.get(eq(host.getJsonArray("addr")[0]), any())
        } returns host["name"]

        runTest(
            deploy = {
                vertx.deployTestVerticle(Router::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                val packet = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                vertx.setPeriodic(100) { vertx.eventBus().localSend(RoutesCE.router, Pair(sender, listOf(packet))) }
            },
            assert = {
                vertx.eventBus().consumer<Packet>(RoutesCE.sip) { event ->
                    val packet = event.body()
                    context.verify {
                        assertTrue(packet is Packet)
                        val src = packet.srcAddr
                        if (src.host != null) {
                            assertEquals(host["name"], src.host)
                            context.completeNow()
                        }
                    }
                }
            }
        )
    }

    @Test
    fun `Apply Groovy UDF to filter SIP packets`() {
        runTest(
            deploy = {
                vertx.deployVerticle(UDF_GROOVY)
                vertx.deployTestVerticle(Router::class, JsonObject().apply {
                    put("udf", JsonObject().apply {
                        put("check-period", 100)
                        put("execution-timeout", 100)
                    })
                })
            },
            execute = {
                val sender1 = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                    host = "test"
                }
                val packet1 = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                val sender2 = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                    host = "sip3-captain"
                }
                val packet2 = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                        host = "Test"
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                vertx.setPeriodic(100) {
                    if (vertx.eventBus().endpoints().contains("packet_udf")) {
                        vertx.eventBus().localSend(RoutesCE.router, Pair(sender1, listOf(packet1)))
                        vertx.eventBus().localSend(RoutesCE.router, Pair(sender2, listOf(packet2)))
                    }
                }
            },
            assert = {
                vertx.eventBus().consumer<Packet>(RoutesCE.sip) { event ->
                    val packet = event.body()
                    context.verify {
                        assertTrue(packet is Packet)
                        assertEquals("Test", packet.srcAddr.host)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Apply Javascript UDF to filter SIP packets`() {
        runTest(
            deploy = {
                vertx.deployVerticle(UDF_JS)
                vertx.deployTestVerticle(Router::class, JsonObject().apply {
                    put("udf", JsonObject().apply {
                        put("check-period", 100)
                        put("execution-timeout", 1000)
                    })
                })
            },
            execute = {
                val sender1 = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                    host = "test"
                }
                val packet1 = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                val sender2 = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                    host = "sip3-captain"
                }
                val packet2 = Packet().apply {
                    this.srcAddr = Address().apply {
                        addr = "29.11.19.88"
                        port = 30
                        host = "Test"
                    }
                    this.dstAddr = Address().apply {
                        addr = "23.08.20.15"
                        port = 3
                    }
                    protocolCode = 3
                }
                vertx.setPeriodic(100) {
                    if (vertx.eventBus().endpoints().contains("packet_udf")) {
                        vertx.eventBus().localSend(RoutesCE.router, Pair(sender1, listOf(packet1)))
                        vertx.eventBus().localSend(RoutesCE.router, Pair(sender2, listOf(packet2)))
                    }
                }
            },
            assert = {
                vertx.eventBus().consumer<Packet>(RoutesCE.sip) { event ->
                    val packet = event.body()
                    context.verify {
                        assertTrue(packet is Packet)
                        assertEquals("Test", packet.srcAddr.host)
                    }
                    context.completeNow()
                }
            }
        )
    }
}