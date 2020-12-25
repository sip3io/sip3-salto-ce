/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import javax.sip.header.ExtensionHeader

class SipMessageHandlerTest : VertxTest() {

    companion object {

        const val UDF_GROOVY = "src/test/resources/udf/SipMessageHandlerTest/SipMessageHandlerTest.groovy"
        const val UDF_JS = "src/test/resources/udf/SipMessageHandlerTest/SipMessageHandlerTest.js"

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
                        Content-Length: 171

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

        // SIP Message without `Call-ID` Header
        val PACKET_2 = Packet().apply {
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
                        SIP/2.0 100 Trying
                        Via: SIP/2.0/UDP 176.9.119.117:5063;branch=z9hG4bK-2196628568-3926998818-1774583950-1258246515;received=176.9.119.117;rport=5063
                        From: <sip:123@176.9.119.117:5063;user=phone>;tag=3997885528-3926998818-1774583950-1258246515
                        To: <sip:321@116.203.55.139;user=phone>
                        CSeq: 1 INVITE
                        Server: Asterisk PBX 13.29.1
                        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE
                        Supported: replaces,timer
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // SIP Message OPTIONS
        val PACKET_3 = Packet().apply {
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
                        OPTIONS sip:1010@192.168.10.123:61540;rinstance=afd8f6ae9bf996b2 SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.5:5060;rport;branch=z9hG4bKPj5464cb3a-5b18-4e5d-97b1-1cbbd70cb879
                        From: <sip:1010@192.168.10.5>;tag=883bc998-e361-4d38-ab78-8a543da457f1
                        To: <sip:1010@192.168.10.123;rinstance=afd8f6ae9bf996b2>
                        Contact: <sip:1010@192.168.10.5:5060>
                        Call-ID: 7061e3c4-d807-4cce-859f-2bef92d9f4e0
                        CSeq: 4358 OPTIONS
                        Max-Forwards: 70
                        User-Agent: FPBX-14.0.13.23(13.29.2)
                        Content-Length:  0

                    """.trimIndent().toByteArray()
        }

        // SIP Message with empty X-Call-ID
        val PACKET_4 = Packet().apply {
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
                        X-Call-ID: 
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
                        Content-Length: 171

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
    fun `Write packet with SIP message to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class)
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_1)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

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
    fun `Handle packet with SIP message based on its method`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class)
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_1)
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (packet, _) = event.body()

                    context.verify {
                        assertEquals(PACKET_1, packet)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle packet with SIP message and read extension header value`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class, JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("message", JsonObject().apply {
                            put("extension-headers", listOf("User-Agent"))
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_1)
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (_, message) = event.body()

                    context.verify {
                        assertEquals("Android Application", (message.getHeader("User-Agent") as ExtensionHeader).value)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle packet with invalid SIP message`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class)
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_2)
            },
            assert = {
                vertx.setPeriodic(200L) {
                    registry.find(RoutesCE.sip + "_invalid_messages").counter()?.let { counter ->
                        context.verify {
                            assertEquals(1.0, counter.count())
                            val tags = counter.id.tags
                            assertTrue(tags.isNotEmpty())
                            assertTrue(tags.any { it.value == "INVITE" })
                        }
                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Handle packet with SIP message and exclude it by CSeq method`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class, JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("message", JsonObject().apply {
                            put("exclusions", listOf("OPTIONS"))
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_3)
                vertx.setPeriodic(1000L) {
                    vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_1)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (packet, _) = event.body()

                    context.verify {
                        assertEquals(PACKET_1, packet)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle packet with SIP message and ignore empty X-Call-ID`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipMessageHandler::class)
            },
            execute = {
                vertx.eventBus().localRequest<Any>(RoutesCE.sip, PACKET_4)
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (packet, message) = event.body()

                    context.verify {
                        assertTrue((message.getHeader("X-Call-ID") as ExtensionHeader).value.isBlank())
                        assertFalse(packet.attributes.containsKey("x_call_id"))
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
                        put("execution-timeout", 100)
                    })
                })
            },
            execute = {
                vertx.setPeriodic(1000) {
                    val packet = Packet().apply {
                        timestamp = PACKET_1.timestamp
                        srcAddr = PACKET_1.srcAddr
                        dstAddr = PACKET_1.dstAddr
                        payload = PACKET_1.payload
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.sip, packet)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (packet, _) = event.body()

                    val attributes = packet.attributes
                    if (attributes.isNotEmpty()) {
                        context.verify {
                            assertEquals(2, attributes.size)
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
                        put("execution-timeout", 100)
                    })
                })
            },
            execute = {
                vertx.setPeriodic(1000) {
                    val packet = Packet().apply {
                        timestamp = PACKET_1.timestamp
                        srcAddr = PACKET_1.srcAddr
                        dstAddr = PACKET_1.dstAddr
                        payload = PACKET_1.payload
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.sip, packet)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, SIPMessage>>(RoutesCE.sip + "_transaction_0") { event ->
                    val (packet, _) = event.body()

                    val attributes = packet.attributes
                    if (attributes.isNotEmpty()) {
                        context.verify {
                            assertEquals(2, attributes.size)
                        }
                        context.completeNow()
                    }
                }
            }
        )
    }
}
