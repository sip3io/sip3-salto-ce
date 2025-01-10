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

package io.sip3.salto.ce.server

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.coroutines.coAwait
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class TcpServerTest : VertxTest() {

    companion object {

        const val MESSAGE_1 = "SIP3 is awesome!"
        val EXTRA_LONG_MESSAGE = MESSAGE_1.repeat(900)
        val NET_CLIENT_OPTIONS = netClientOptionsOf(reconnectInterval = 100L, reconnectAttempts = 100)
    }

    @Test
    fun `Retrieve SIP3 packet via TCP`() {
        val port = findRandomPort()
        runTest(
            deploy = {
                vertx.deployTestVerticle(TcpServer::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "tcp://127.0.0.1:$port")
                    })
                })
            },
            execute = {
                vertx.createNetClient(NET_CLIENT_OPTIONS)
                    .connect(port, "127.0.0.1").coAwait()
                    .write(Buffer.buffer(MESSAGE_1).appendString("\r\n\r\n3PIS\r\n\r\n"))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.sip3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.0.1", sender.addr)
                        assertEquals(UdpServerTest.MESSAGE_1, buffer.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Retrieve big SIP3 packet via TCP`() {
        val port = findRandomPort()
        runTest(
            deploy = {
                vertx.deployTestVerticle(TcpServer::class, JsonObject().apply {
                    put("server", JsonObject().apply {
                        put("uri", "tcp://127.0.0.1:$port")
                    })
                })
            },
            execute = {
                vertx.createNetClient(NET_CLIENT_OPTIONS)
                    .connect(port, "127.0.0.1").coAwait()
                    .write(Buffer.buffer(EXTRA_LONG_MESSAGE).appendString("\r\n\r\n3PIS\r\n\r\n"))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.sip3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.0.1", sender.addr)
                        assertEquals(EXTRA_LONG_MESSAGE, buffer.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }
}