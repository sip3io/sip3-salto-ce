/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

import io.sip3.commons.domain.SdpSession
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.ServerSocket
import java.util.*

@ExtendWith(MongoExtension::class)
class ManagementSocketTest : VertxTest() {

    companion object {

        private val HOST = JsonObject().apply {
            put("name", "sbc1")
            put("sip", arrayListOf("10.10.10.10", "10.10.20.10:5060"))
        }

        private val REGISTER_MESSAGE = JsonObject().apply {
            put("type", ManagementSocket.TYPE_REGISTER)
            put("payload", JsonObject().apply {
                put("name", UUID.randomUUID().toString())
                put("host", HOST)
            })
        }
    }

    private lateinit var config: JsonObject
    private var localPort = -1
    private var remotePort = -1

    @BeforeEach
    fun init() {
        // TODO: Move free port selection to JUnit5 Extension (https://github.com/sip3io/sip3-salto-ce/issues/5)
        val localSocket = ServerSocket(0)
        localPort = localSocket.localPort
        val remoteSocket = ServerSocket(0)
        remotePort = remoteSocket.localPort
        localSocket.close()
        remoteSocket.close()

        config = JsonObject().apply {
            put("mongo", JsonObject().apply {
                put("uri", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                put("db", "sip3")
                put("bulk-size", 1)
            })
            put("management", JsonObject().apply {
                put("uri", "udp://127.0.0.1:$localPort")
                put("expiration-timeout", 1500L)
                put("expiration-delay", 800L)
            })
        }
    }

    @Test
    fun `Receive register from remote host with host information`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(ManagementSocket::class, config)
                },
                execute = {
                    vertx.createDatagramSocket().send(REGISTER_MESSAGE.toBuffer(), localPort, "127.0.0.1") {}
                },
                assert = {
                    val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                        put("connection_string", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                        put("db_name", "sip3")
                    })

                    vertx.setPeriodic(500) {
                        mongo.findOne("hosts", HOST, JsonObject()) { asr ->
                            if (asr.succeeded() && asr.result() != null) {
                                context.completeNow()
                            }
                        }
                    }
                }
        )
    }

    @Test
    fun `Send SDP session to registered remote host`() {
        val sdpSession = SdpSession().apply {
            id = 10070L
            timestamp = System.currentTimeMillis()
            clockRate = 8000
            codecIe = 1F
            codecBpl = 2F
            payloadType = 0
            callId = "SomeKindOfCallId"
        }

        lateinit var socket: DatagramSocket

        runTest(
                deploy = {
                    vertx.deployTestVerticle(ManagementSocket::class, config)
                },
                execute = {
                    socket.send(REGISTER_MESSAGE.toBuffer(), localPort, "127.0.0.1") {
                        vertx.eventBus().send(RoutesCE.sdp_session, listOf(sdpSession), USE_LOCAL_CODEC)
                    }
                },
                assert = {
                    socket = vertx.createDatagramSocket()
                    socket.handler { packet ->
                        context.verify {
                            val message = packet.data().toJsonObject()
                            assertEquals(ManagementSocket.TYPE_SDP_SESSION, message.getString("type"))
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
}