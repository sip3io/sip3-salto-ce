/*
 * Copyright 2018-2024 SIP3.IO, Corp.
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
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.json.JsonObject

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI

class AbstractServerTest : VertxTest() {

    @Test
    fun `Validate 'handle()' method`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(ServerTestImpl::class, JsonObject().apply {
                    put("name", "name")
                    put("a", "value1")
                    put("b", "value2")
                    put("message", JsonObject().apply {
                        put("enabled", true)
                    })
                })
            },
            execute = {

            },
            assert = {
                vertx.eventBus().localConsumer<Pair<URI, JsonObject>>(RoutesCE.management) { event ->
                    val (uri, message) = event.body()
                    context.verify {
                        assertEquals("tcp://127.0.0.1:4567", uri.toString())
                        assertTrue(message.getJsonObject("message").getBoolean("enabled"))
                    }

                    event.localReply(JsonObject().apply {
                        put("type", "register_response")
                        put("payload", JsonObject())
                    })
                }

                vertx.eventBus().localConsumer<Pair<JsonObject, List<URI>>>("test_validate") { event ->
                    val (message, uris) = event.body()
                    context.verify {
                        assertEquals("register_response", message.getString("type"))
                        assertNotNull(message.getJsonObject("payload"))
                        assertEquals(1, uris.size)
                        assertEquals("tcp://127.0.0.1:4567", uris.first().toString())
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
    lateinit var message: JsonObject

    lateinit var server: String

    override fun readConfig() {
        a = config().getString("a")
        b = config().getString("b")
        message = config().getJsonObject("message")
    }

    override fun startServer() {
        server = "SomeServer-$a-$b"

        val uri = URI("tcp://127.0.0.1:4567")
        vertx.setPeriodic(100L, 100L) {
            handle(uri, JsonObject().apply {
                put("message", message)
            })
        }
    }
    override fun send(message: JsonObject, uris: List<URI>) {
        vertx.eventBus().localSend("test_validate", Pair(message, uris))
    }
}