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

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localReply
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.*

class TcpServerTest : VertxTest() {

    companion object {

        private const val DELIMITER = "\r\n\r\n3PIS\r\n\r\n"

        private val HOST = JsonObject().apply {
            put("name", "sbc1")
            put("addr", JsonArray().apply {
                add("10.10.10.10")
                add("10.10.20.10:5060")
            })
        }

        private val CONFIG = JsonObject().apply {
            put("management", JsonObject().apply {
                put("uri", "tcp://127.0.0.1:15090")
                put("register_delay", 2000L)
            })
            put("host", HOST)
            put("rtp", JsonObject().apply {
                put("enabled", true)
            })
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
                put("uri", "tcp://127.0.0.1:15060")
            })

            put("management", JsonObject().apply {
                put("uri", "tcp://127.0.0.1:$localPort")
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
        }
    }

    @Test
    fun `Receive register from remote host with host information`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(TcpServer::class, config)
            },
            execute = {
                vertx.createNetClient().connect(localPort, "127.0.0.1").onSuccess { socket ->
                    socket.handler { buffer ->
                        val text = buffer.toString()
                        context.verify {
                            assertTrue(text.endsWith(DELIMITER))
                            val response = JsonObject(text.replace(DELIMITER, ""))
                            assertEquals("register_response", response.getString("type"))
                            assertNotNull(response.getJsonObject("payload"))
                        }
                        context.completeNow()
                    }

                    socket.write(REGISTER_MESSAGE_1.toBuffer().appendString(DELIMITER))
                }

            },
            assert = {
                vertx.eventBus().localConsumer<Pair<URI, JsonObject>>(RoutesCE.management) { event ->
                    val (uri, message) = event.body()
                    context.verify {
                        assertEquals(REGISTER_MESSAGE_1.getString("type"), message.getString("type"))
                    }
                    event.localReply(JsonObject().apply {
                        put("type", "register_response")
                        put("payload", JsonObject())
                    })
                }
            }
        )
    }
}