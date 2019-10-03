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

package io.sip3.salto.ce.server

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.Routes
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.datagram.sendAwait
import io.vertx.kotlin.core.net.connectAwait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class ServerTest : VertxTest() {

    companion object {

        const val MESSAGE_1 = "SIP3 is awesome!"
        const val MESSAGE_2 = "HEP3 is awesome!"
    }

    @Test
    fun `Retrieve SIP3 packet via UDP`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Server::class, JsonObject().apply {
                        put("server", JsonObject().apply {
                            put("uri", "udp://127.0.0.1:15060")
                        })
                    })
                },
                execute = {
                    vertx.createDatagramSocket().sendAwait(MESSAGE_1, 15060, "127.0.0.1")
                },
                assert = {
                    vertx.eventBus().consumer<Buffer>(Routes.sip3) { event ->
                        val buffer = event.body()
                        context.verify {
                            assertEquals(MESSAGE_1, buffer.toString(Charset.defaultCharset()))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Retrieve HEP3 packet via TCP`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Server::class, JsonObject().apply {
                        put("server", JsonObject().apply {
                            put("uri", "tcp://127.0.0.1:15060")
                        })
                    })
                },
                execute = {
                    vertx.createNetClient().connectAwait(15060, "127.0.0.1").write(MESSAGE_2)
                },
                assert = {
                    vertx.eventBus().consumer<Buffer>(Routes.hep3) { event ->
                        val buffer = event.body()
                        context.verify {
                            assertEquals(MESSAGE_2, buffer.toString(Charset.defaultCharset()))
                        }
                        context.completeNow()
                    }
                }
        )
    }
}