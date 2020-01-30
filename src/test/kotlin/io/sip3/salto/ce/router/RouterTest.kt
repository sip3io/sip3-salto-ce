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

package io.sip3.salto.ce.router

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.ext.mongo.saveAwait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MongoExtension::class)
class RouterTest : VertxTest() {

    @Test
    fun `Route SIP packet without host mapping`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Router::class)
                },
                execute = {
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
                    vertx.eventBus().send(RoutesCE.router, packet, USE_LOCAL_CODEC)
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
    fun `Send packet attributes without host mapping`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Router::class, JsonObject().apply {
                        put("attributes", JsonObject().apply {
                            put("record-ip-addresses", true)
                        })
                    })
                },
                execute = {
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
                    vertx.eventBus().send(RoutesCE.router, packet, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
                        val (prefix, attributes) = event.body()
                        context.verify {
                            assertEquals("ip", prefix)
                            assertEquals(2, attributes.size)
                            assertEquals("29.11.19.88", attributes["src_addr"])
                            assertEquals("23.08.20.15", attributes["dst_addr"])
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
            put("sip", JsonArray().apply {
                add("29.11.19.88")
            })
        }

        runTest(
                deploy = {
                    val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                        put("connection_string", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                        put("db_name", "sip3")
                    })
                    mongo.saveAwait("hosts", host)

                    vertx.deployTestVerticle(Router::class, JsonObject().apply {
                        put("mongo", JsonObject().apply {
                            put("uri", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                            put("db", "sip3")
                        })
                    })
                },
                execute = {
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
                    vertx.setPeriodic(100) { vertx.eventBus().send(RoutesCE.router, packet, USE_LOCAL_CODEC) }
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
    fun `Send packet attributes with host mapping`() {
        val host = JsonObject().apply {
            put("name", "test")
            put("sip", JsonArray().apply {
                add("29.11.19.88")
            })
        }

        runTest(
                deploy = {
                    val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                        put("connection_string", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                        put("db_name", "sip3")
                    })
                    mongo.saveAwait("hosts", host)

                    vertx.deployTestVerticle(Router::class, JsonObject().apply {
                        put("mongo", JsonObject().apply {
                            put("uri", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                            put("db", "sip3")
                        })
                        put("attributes", JsonObject().apply {
                            put("record-ip-addresses", true)
                        })
                    })
                },
                execute = {
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
                    vertx.setPeriodic(100) { vertx.eventBus().send(RoutesCE.router, packet, USE_LOCAL_CODEC) }
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
                        val (prefix, attributes) = event.body()
                        context.verify {
                            assertEquals("ip", prefix)
                            assertEquals(3, attributes.size)
                            assertEquals("29.11.19.88", attributes["src_addr"])
                            assertEquals("test", attributes["src_host"])
                            assertEquals("23.08.20.15", attributes["dst_addr"])
                        }
                        context.completeNow()
                    }
                }
        )
    }
}