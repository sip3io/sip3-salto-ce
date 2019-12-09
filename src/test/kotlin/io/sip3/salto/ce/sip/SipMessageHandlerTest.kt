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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.Routes
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class SipMessageHandlerTest : VertxTest() {

    companion object {

        const val UDF_GROOVY = "src/test/resources/udf/SipMessageHandlerTest.groovy"
        const val UDF_JS = "src/test/resources/udf/SipMessageHandlerTest.js"

        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        INVITE sip:000155917690@ss63.invite.demo.sip3.io:5060 SIP/2.0
                        Via: SIP/2.0/UDP 10.177.131.211:6333;branch=z9hG4bKmqffet30b03pp5mv5jj0.1
                        From: <sip:000260971282@demo.sip3.io>;tag=82-2zyzysoabqjb3
                        To: <sip:000155917690@demo.sip3.io:5060>
                        Call-ID: 2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e
                        CSeq: 1 INVITE
                        Contact: <sip:signode-82-gxp92pqazkbzz@10.177.131.211:6333;transport=udp>
                        Allow: INVITE,ACK,CANCEL,BYE,INFO,REFER,SUBSCRIBE,NOTIFY
                        Allow-Events: keep-alive
                        Supported: timer
                        Session-Expires: 7200
                        Expires: 300
                        Min-SE: 900
                        Max-Forwards: 63
                        User-Agent: Android Application
                        Content-Type: application/sdp
                        Content-Length: 179

                        v=0
                        o=- 677480114 3140674329 IN IP4 10.177.131.228
                        s=centrex-mediagateway
                        t=0 0
                        m=audio 35176 RTP/AVP 8
                        c=IN IP4 10.177.131.228
                        a=rtpmap:8 PCMA/8000
                        a=sendrecv
                        a=ptime:20

                    """.trimIndent().toByteArray()
        }
    }

    @Test
    fun `Send packet with SIP message to database`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SipMessageHandler::class)
                },
                execute = {
                    vertx.eventBus().send(Routes.sip, PACKET_1, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(Routes.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()

                        document = document.getJsonObject("document")

                        context.verify {
                            assertTrue(collection.startsWith("sip_call_raw_"))

                            assertEquals("127.0.0.1", document.getString("src_addr"))
                            assertEquals(5060, document.getInteger("src_port"))
                            assertEquals("127.0.0.1", document.getString("dst_addr"))
                            assertEquals(5060, document.getInteger("dst_port"))
                            assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e", document.getString("call_id"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Send packet with SIP message to further handler`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SipMessageHandler::class)
                },
                execute = {
                    vertx.eventBus().send(Routes.sip, PACKET_1, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(Routes.sip + "_call_0") { event ->
                        var (packet, message) = event.body()

                        context.verify {
                            assertEquals(PACKET_1, packet)
                            assertTrue(message is SIPMessage)
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Apply Groovy UDF to packet with SIP message`() {
        runTest(
                deploy = {
                    vertx.deployVerticle(UDF_GROOVY)
                    vertx.deployTestVerticle(SipMessageHandler::class, JsonObject().apply {
                        put("udf", JsonObject().apply {
                            put("check-period", 100)
                            put("execute-timeout", 100)
                        })
                    })
                },
                execute = {
                    val packet = Packet().apply {
                        timestamp = PACKET_1.timestamp
                        srcAddr = PACKET_1.srcAddr
                        dstAddr = PACKET_1.dstAddr
                        payload = PACKET_1.payload
                    }
                    vertx.setPeriodic(100) {
                        vertx.eventBus().send(Routes.sip, packet, USE_LOCAL_CODEC)
                    }
                },
                assert = {
                    vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(Routes.sip + "_call_0") { event ->
                        var (packet, _) = event.body()

                        val attributes = packet.attributes
                        if (attributes.isNotEmpty()) {
                            context.verify {
                                assertEquals(3, attributes.size)
                            }
                            context.completeNow()
                        }
                    }
                }
        )
    }

    @Test
    fun `Apply JavaScript UDF to packet with SIP message`() {
        runTest(
                deploy = {
                    vertx.deployVerticle(UDF_JS)
                    vertx.deployTestVerticle(SipMessageHandler::class, JsonObject().apply {
                        put("udf", JsonObject().apply {
                            put("check-period", 100)
                            put("execute-timeout", 100)
                        })
                    })
                },
                execute = {
                    val packet = Packet().apply {
                        timestamp = PACKET_1.timestamp
                        srcAddr = PACKET_1.srcAddr
                        dstAddr = PACKET_1.dstAddr
                        payload = PACKET_1.payload
                    }
                    vertx.setPeriodic(100) {
                        vertx.eventBus().send(Routes.sip, packet, USE_LOCAL_CODEC)
                    }
                },
                assert = {
                    vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(Routes.sip + "_call_0") { event ->
                        var (packet, _) = event.body()

                        val attributes = packet.attributes
                        if (attributes.isNotEmpty()) {
                            context.verify {
                                assertEquals(3, attributes.size)
                            }
                            context.completeNow()
                        }
                    }
                }
        )
    }
}