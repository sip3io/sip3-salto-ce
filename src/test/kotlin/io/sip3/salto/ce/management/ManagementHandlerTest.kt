/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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

import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.commons.domain.media.*
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localRequest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.MockKSingletonExtension
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.management.component.ComponentRegistry
import io.sip3.salto.ce.management.host.HostRegistry
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.util.*

@ExtendWith(MockKExtension::class, MockKSingletonExtension::class)
class ManagementHandlerTest : VertxTest() {

    companion object {

        private val HOST = JsonObject().apply {
            put("name", "sbc1")
            put("addr", JsonArray().apply {
                add("10.10.10.10")
                add("10.10.20.10:5060")
            })
        }

        private val CONFIG = JsonObject().apply {
            put("management", JsonObject().apply {
                put("uri", "udp://127.0.0.1:15090")
                put("register_delay", 2000L)
            })
            put("host", HOST)
            put("rtp", JsonObject().apply {
                put("enabled", true)
            })
            put("version", "2025.1.1")
        }

        private val DEPLOYMENT_ID = UUID.randomUUID().toString()

        private val REGISTER_MESSAGE_1 = JsonObject().apply {
            put("type", ManagementHandler.TYPE_REGISTER)
            put("payload", JsonObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("deployment_id", DEPLOYMENT_ID)
                put("config", CONFIG)
            })
        }

        private val REGISTER_MESSAGE_2 = JsonObject().apply {
            put("type", ManagementHandler.TYPE_REGISTER)
            put("payload", JsonObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("name", DEPLOYMENT_ID)
                put("config", CONFIG.copy().apply {
                    remove("host")
                })
            })
        }
    }

    private lateinit var config: JsonObject
    private var localPort = -1
    private var remotePort = -1

    @BeforeEach
    fun init() {
        localPort = findRandomPort()
        remotePort = findRandomPort()
        config = JsonObject().apply {
            put("name", "sip3-salto-unit-test")

            put("server", JsonObject().apply {
                put("uri", "udp://127.0.0.1:15060")
            })

            put("management", JsonObject().apply {
                put("uri", "udp://127.0.0.1:$localPort")
                put("expiration_timeout", 1500L)
                put("expiration_delay", 800L)
            })

            put("mongo", JsonObject().apply {
                put("management", JsonObject().apply {
                    put("uri", "mongodb://superhost.com:10000/?w=1")
                    put("db", "salto-component-management-test")
                })
                put("uri", "mongodb://superhost.com:20000/?w=1")
                put("db", "salto-component-test")
            })
            put("version", "2025.1.1")
        }
    }

    @Test
    fun `Receive register from remote host with host information`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {},
            assert = {
                val uri = URI("udp://127.0.0.1:$remotePort")
                vertx.setPeriodic(100L) {
                    vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_1)) { asr ->
                        val response = asr.result()?.body() ?: return@localRequest
                        context.verify {
                            assertEquals("register_response", response.getString("type"))
                            response.getJsonObject("payload").let { payload ->
                                assertEquals("registered", payload.getString("status"))
                                assertNotNull(payload.getLong("registered_at"))
                            }

                            verify(atLeast = 1) {
                                HostRegistry.save(any())
                            }
                            verify(atLeast = 1) {
                                ComponentRegistry.save(any())
                            }
                        }

                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Save Salto component on start`() {
        val componentSlot: CapturingSlot<JsonObject> = slot()
        every {
            ComponentRegistry.save(any())
        } just Runs
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {},
            assert = {
                vertx.setPeriodic(500, 100) {
                    context.verify {
                        verify(atLeast = 1) {
                            ComponentRegistry.save(capture(componentSlot))
                        }
                        assertTrue(componentSlot.isCaptured)
                        val component = componentSlot.captured

                        assertEquals("sip3-salto-unit-test", component.getString("name"))
                        assertEquals("salto", component.getString("type"))
                        assertEquals(2, component.getJsonArray("uri").size())
                        assertEquals(2, component.getJsonArray("connected_to").size())
                        assertEquals("mongodb://superhost.com:10000/salto-component-management-test", component.getJsonArray("connected_to").getString(0))
                        assertEquals("mongodb://superhost.com:20000/salto-component-test", component.getJsonArray("connected_to").getString(1))
                        assertEquals("2025.1.1", component.getString("version"))
                        context.completeNow()
                    }
                }
            }
        )
    }



    @Test
    fun `Receive register from remote host without host information and 'deployment_id'`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        val uri = URI("udp://127.0.0.1:$remotePort")
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {},
            assert = {
                vertx.setPeriodic(100L) {
                    vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_2)) { asr ->
                        val response = asr.result()?.body() ?: return@localRequest
                        context.verify {
                            assertEquals("register_response", response.getString("type"))
                            response.getJsonObject("payload").let { payload ->
                                assertEquals("registered", payload.getString("status"))
                                assertNotNull(payload.getLong("registered_at"))
                            }

                            verify(exactly = 0) {
                                HostRegistry.save(any())
                            }
                            verify(atLeast = 1) {
                                ComponentRegistry.save(any())
                            }
                        }

                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Send Media Control to registered remote host`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        // Media Control
        val mediaControl = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "SomeKindOfCallId"
            caller = "123"
            callee = "456"

            sdpSession = SdpSession().apply {
                src = MediaAddress().apply {
                    addr = "127.0.0.1"
                    rtpPort = 1000
                    rtcpPort = 1001
                }
                dst = MediaAddress().apply {
                    addr = "127.0.0.2"
                    rtpPort = 2000
                    rtcpPort = 2001
                }

                codecs = mutableListOf(Codec().apply {
                    name = "PCMU"
                    payloadTypes = listOf(0)
                    clockRate = 8000
                    bpl = 4.3F
                    ie = 0F
                })
            }

            recording = Recording()
        }

        val uri = URI("udp://127.0.0.1:$remotePort")
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {
                vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_1)) { asr ->
                    if (asr.result() == null) return@localRequest

                    vertx.setTimer(100) {
                        vertx.eventBus().localSend(RoutesCE.media + "_control", mediaControl)
                    }
                }
            },
            assert = {
                vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>(RoutesCE.management + "_send") { event ->
                    val (message, dstUri) = event.body()
                    context.verify {
                        assertEquals(1, dstUri.size)
                        assertEquals(uri, dstUri.first())
                        message.apply {
                            assertEquals(ManagementHandler.TYPE_MEDIA_CONTROL, message.getString("type"))
                        }
                    }
                    context.completeNow()
                }
            },
            cleanup = {
            }
        )
    }

    @Test
    fun `Send 'media_recording_reset' command to agents`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        val uri = URI("udp://127.0.0.1:$remotePort")
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {
                vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_1)) { asr ->
                    if (asr.result() == null) return@localRequest

                    vertx.setTimer(100) {
                        vertx.eventBus().localSend(RoutesCE.media + "_recording_reset", JsonObject())
                    }
                }
            },
            assert = {
                vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>(RoutesCE.management + "_send") { event ->
                    val (message, dstUri) = event.body()
                    context.verify {
                        assertEquals(1, dstUri.size)
                        assertEquals(uri, dstUri.first())
                        message.apply {
                            assertEquals(ManagementHandler.TYPE_MEDIA_RECORDING_RESET, message.getString("type"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Send 'media_recording_reset' command to agent with deployment_id`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        val uri = URI("udp://127.0.0.1:$remotePort")
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {
                vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_1)) { asr ->
                    if (asr.result() == null) return@localRequest

                    vertx.setTimer(100) {
                        vertx.eventBus().localSend(RoutesCE.media + "_recording_reset", JsonObject().apply {
                            put("deployment_id", "1234")
                        })

                        vertx.eventBus().localSend(RoutesCE.media + "_recording_reset", JsonObject().apply {
                            put("deployment_id", DEPLOYMENT_ID)
                        })
                    }
                }
            },
            assert = {
                vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>(RoutesCE.management + "_send") { event ->
                    val (message, dstUri) = event.body()
                    context.verify {
                        assertEquals(1, dstUri.size)
                        assertEquals(uri, dstUri.first())
                        message.apply {
                            assertEquals(ManagementHandler.TYPE_MEDIA_RECORDING_RESET, getString("type"))
                            assertEquals(DEPLOYMENT_ID, getJsonObject("payload").getString("deployment_id"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }


    @Test
    fun `Send 'shutdown' command to agent with deployment_id`() {
        every {
            HostRegistry.save(any())
        } just Runs

        every {
            ComponentRegistry.save(any())
        } just Runs

        val uri = URI("udp://127.0.0.1:$remotePort")
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementHandler::class, config)
            },
            execute = {
                vertx.eventBus().localRequest<JsonObject?>(RoutesCE.management, Pair(uri, REGISTER_MESSAGE_1)) { asr ->
                    if (asr.result() == null) return@localRequest

                    vertx.setTimer(100) {
                        vertx.eventBus().localSend(RoutesCE.management, Pair(URI("udp://some_uri"), JsonObject().apply {
                            put("type", ManagementHandler.TYPE_SHUTDOWN)
                            put("payload", JsonObject().apply {
                                put("deployment_id", DEPLOYMENT_ID)
                                put("exit_code", 1)
                            })
                        }))
                    }
                }
            },
            assert = {
                vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>(RoutesCE.management + "_send") { event ->
                    val (message, dstUri) = event.body()
                    context.verify {
                        assertEquals(1, dstUri.size)
                        assertEquals(uri, dstUri.first())
                        message.apply {
                            assertEquals(ManagementHandler.TYPE_SHUTDOWN, getString("type"))
                            assertEquals(DEPLOYMENT_ID, getJsonObject("payload").getString("deployment_id"))
                            assertEquals(1, getJsonObject("payload").getInteger("exit_code"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

}