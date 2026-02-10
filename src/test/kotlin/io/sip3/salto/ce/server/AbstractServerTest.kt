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

package io.sip3.salto.ce.server

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class AbstractServerTest : VertxTest() {

    companion object {

        const val MESSAGE_1 = "SIP3 is awesome!"
        const val MESSAGE_2 = "HEP3 is awesome!"
        val MESSAGE_3 = byteArrayOf(0x02, 0x10, 0x02, 0x42).toString(Charset.defaultCharset())
    }

    @Test
    fun `Handle SIP3 Packet from server`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(ServerTestImpl::class, JsonObject().apply {
                    put("a", "value1")
                    put("b", "value2")
                    put("message", MESSAGE_1)
                })
            },
            execute = { },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.sip3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.1.2", sender.addr)
                        assertEquals(3456, sender.port)
                        assertEquals("$MESSAGE_1-value1-value2", buffer.toString())
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle HEP3 Packet from server`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(ServerTestImpl::class, JsonObject().apply {
                    put("a", "value1")
                    put("b", "value2")
                    put("message", MESSAGE_2)
                })
            },
            execute = { },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.hep3) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.1.2", sender.addr)
                        assertEquals(3456, sender.port)
                        assertEquals("$MESSAGE_2-value1-value2", buffer.toString())
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle HEP2 Packet from server`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(ServerTestImpl::class, JsonObject().apply {
                    put("a", "value1")
                    put("b", "value2")
                    put("message", MESSAGE_3)
                })
            },
            execute = { },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Buffer>>(RoutesCE.hep2) { event ->
                    val (sender, buffer) = event.body()
                    context.verify {
                        assertEquals("127.0.1.2", sender.addr)
                        assertEquals(3456, sender.port)
                        assertEquals("$MESSAGE_3-value1-value2", buffer.toString())
                    }
                    context.completeNow()
                }
            }
        )
    }
}

class ServerTestImpl : AbstractServer() {

    lateinit var a: String
    lateinit var b: String
    lateinit var message: String

    lateinit var server: String

    override fun readConfig() {
        a = config().getString("a")
        b = config().getString("b")
        message = config().getString("message")
    }

    override fun startServer() {
        server = "SomeServer-$a-$b"

        val address = Address().apply {
            addr = "127.0.1.2"
            port = 3456
        }
        vertx.setPeriodic(100L, 100L) {
            onRawPacket(address, Buffer.buffer("$message-$a-$b"))
        }
    }
}