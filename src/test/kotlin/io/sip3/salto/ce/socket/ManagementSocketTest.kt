/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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

package io.sip3.salto.ce.socket

import io.mockk.Runs
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import io.sip3.commons.domain.media.*
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.MockKSingletonExtension
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.host.HostRegistry
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.*

@ExtendWith(MockKExtension::class, MockKSingletonExtension::class)
class ManagementSocketTest : VertxTest() {

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
                put("register-delay", 2000L)
            })
            put("host", HOST)
            put("rtp", JsonObject().apply {
                put("enabled", true)
            })
        }

        private val REGISTER_MESSAGE = JsonObject().apply {
            put("type", ManagementSocket.TYPE_REGISTER)
            put("payload", JsonObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("name", UUID.randomUUID().toString())
                put("config", CONFIG)
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
            put("management", JsonObject().apply {
                put("uri", "udp://127.0.0.1:$localPort")
                put("expiration-timeout", 1500L)
                put("expiration-delay", 800L)
            })
        }
    }

    @Test
    fun `Receive register from remote host with host information`() {
        every {
            HostRegistry.save(any())
        } just Runs

        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementSocket::class, config)
            },
            execute = {
                vertx.createDatagramSocket().send(REGISTER_MESSAGE.toBuffer(), localPort, "127.0.0.1").await()
            },
            assert = {
                vertx.setPeriodic(500, 100) {
                    context.verify {
                        verify(atLeast = 1) {
                            HostRegistry.save(any())
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

        lateinit var socket: DatagramSocket

        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementSocket::class, config)
            },
            execute = {
                socket.send(REGISTER_MESSAGE.toBuffer(), localPort, "127.0.0.1").await()

                vertx.setTimer(100) {
                    vertx.eventBus().localSend(RoutesCE.media + "_control", mediaControl)
                }
            },
            assert = {
                socket = vertx.createDatagramSocket()
                socket.handler { packet ->
                    context.verify {
                        val message = packet.data().toJsonObject()
                        assertEquals(ManagementSocket.TYPE_MEDIA_CONTROL, message.getString("type"))
                    }
                    context.completeNow()
                }
                socket.listen(remotePort, "127.0.0.1") {}
            },
            cleanup = {
                socket.close()
            }
        )
    }

    @Test
    fun `Send 'media_recording_reset' command to agents`() {
        every {
            HostRegistry.save(any())
        } just Runs

        lateinit var socket: DatagramSocket
        runTest(
            deploy = {
                vertx.deployTestVerticle(ManagementSocket::class, config)
            },
            execute = {
                socket.send(REGISTER_MESSAGE.toBuffer(), localPort, "127.0.0.1").await()
                vertx.setTimer(100) {
                    vertx.eventBus().localSend(RoutesCE.media + "_recording_reset", JsonObject())
                }
            },
            assert = {
                socket = vertx.createDatagramSocket()
                socket.listen(remotePort, "127.0.0.1")
                socket.handler { packet ->
                    context.verify {
                        packet.data().toJsonObject().apply {
                            assertEquals(ManagementSocket.TYPE_MEDIA_RECORDING_RESET, getString("type"))
                        }
                        context.completeNow()
                    }
                }
            },
            cleanup = {
                socket.close()
            }
        )
    }
}