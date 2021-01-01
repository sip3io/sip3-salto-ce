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

package io.sip3.salto.ce.server

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class ServerTest : VertxTest() {

    companion object {

        const val MESSAGE_1 = "SIP3 is awesome!"
        const val MESSAGE_2 = "HEP3 is awesome!"
        val MESSAGE_3 = byteArrayOf(0x02, 0x10, 0x02, 0x42)
    }

    @Test
    fun `Retrieve SIP3 packet via UDP`() {
        val port = findRandomPort()
        runTest(
            deploy = {
                vertx.deployTestVerticle(Server::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "udp://127.0.0.1:$port")
                    })
                })
            },
            execute = {
                vertx.createDatagramSocket().send(MESSAGE_1, port, "127.0.0.1").await()
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.sip3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.0.1", sender.addr)
                        assertEquals(MESSAGE_1, buffer.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Retrieve HEP3 packet via TCP`() {
        val port = findRandomPort()
        runTest(
            deploy = {
                vertx.deployTestVerticle(Server::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "tcp://127.0.0.1:$port")
                    })
                })
            },
            execute = {
                vertx.createNetClient().connect(port, "127.0.0.1").await().write(MESSAGE_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.hep3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.0.1", sender.addr)
                        assertEquals(MESSAGE_2, buffer.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Retrieve HEP2 packet via TCP`() {
        val port = findRandomPort()
        runTest(
            deploy = {
                vertx.deployTestVerticle(Server::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "tcp://127.0.0.1:$port")
                    })
                })
            },
            execute = {
                vertx.createNetClient().connect(port, "127.0.0.1").await().write(Buffer.buffer(MESSAGE_3))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.hep2) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.0.1", sender.addr)
                        assertArrayEquals(MESSAGE_3, buffer.bytes)
                    }
                    context.completeNow()
                }
            }
        )
    }
}