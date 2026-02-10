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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.endpoints
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class SipTransactionHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        val PACKET_OPTIONS_1 = Packet().apply {
            createdAt = NOW
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            attributes = mutableMapOf()
            attributes!![Attributes.caller] = "caller"
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

        val PACKET_OPTIONS_2 = Packet().apply {
            createdAt = NOW + 25
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 503 Service Unavailable
                        Via: SIP/2.0/UDP 192.168.10.5:5060;rport=5060;branch=z9hG4bKPj5464cb3a-5b18-4e5d-97b1-1cbbd70cb879
                        Contact: <sip:192.168.10.123:61540>
                        To: <sip:1010@192.168.10.123;rinstance=afd8f6ae9bf996b2>;tag=4a2be263
                        From: <sip:1010@192.168.10.5>;tag=883bc998-e361-4d38-ab78-8a543da457f1
                        Call-ID: 7061e3c4-d807-4cce-859f-2bef92d9f4e0
                        CSeq: 4358 OPTIONS
                        Accept: application/sdp
                        Accept-Language: en
                        Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REGISTER, SUBSCRIBE, NOTIFY, REFER, INFO, MESSAGE
                        Supported: replaces
                        Allow-Events: presence, message-summary, tunnel-info
                        Content-Length: 0

                    """.trimIndent().toByteArray()
        }

        val PACKET_MESSAGE_1 = Packet().apply {
            createdAt = NOW
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            attributes = mutableMapOf()
            attributes!![Attributes.caller] = "caller"
            payload = """
                        MESSAGE sip:user2@domain.com SIP/2.0
                        Via: SIP/2.0/TCP user1pc.domain.com;branch=z9hG4bK776sgdkse
                        Max-Forwards: 70
                        From: sip:user1@domain.com;tag=49583
                        To: sip:user2@domain.com
                        Call-ID: asd88asd77a@1.2.3.4
                        CSeq: 1 MESSAGE
                        Content-Type: text/plain
                        Content-Length: 18
                        
                        Watson, come here.
                        
                    """.trimIndent().toByteArray()
        }

        val PACKET_MESSAGE_2 = Packet().apply {
            createdAt = NOW + 25
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/TCP user1pc.domain.com;branch=z9hG4bK776sgdkse;received=1.2.3.4
                        From: sip:user1@domain.com;;tag=49394
                        To: sip:user2@domain.com;tag=ab8asdasd9
                        Call-ID: asd88asd77a@1.2.3.4
                        CSeq: 1 MESSAGE
                        Content-Length: 0

                    """.trimIndent().toByteArray()
        }

        // INVITE
        val FAILED_PACKET_1 = Packet().apply {
            createdAt = SipCallHandlerTest.NOW
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            attributes = mutableMapOf()
            attributes!![Attributes.caller] = "caller"
            payload = """
                        INVITE sip:321@116.203.55.139;user=phone SIP/2.0
                        Via: SIP/2.0/UDP 176.9.119.117:5063;rport;branch=z9hG4bK-2196628568-3926998818-1774583950-1258246515
                        From: <sip:123@176.9.119.117:5063;user=phone>;tag=3997885528-3926998818-1774583950-1258246515
                        To: <sip:321@116.203.55.139;user=phone>
                        Call-ID: 58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117
                        CSeq: 1 INVITE
                        Allow: ACK,BYE,CANCEL,INFO,INVITE,OPTIONS,REFER,REGISTER,UPDATE
                        Max-Forwards: 70
                        Content-Type: application/sdp
                        Content-Length: 334

                        Content-Type: application/sdp

                        v=0
                        o=- 1576746605 1576746605 IN IP4 176.9.119.117
                        s=-
                        c=IN IP4 176.9.119.117
                        t=0 0
                        m=audio 41504 RTP/AVP 8 18 0 96
                        a=rtpmap:8 PCMA/8000
                        a=rtpmap:18 G729/8000
                        
                    """.trimIndent().toByteArray()
        }

        // 100 Trying
        val FAILED_PACKET_2 = Packet().apply {
            createdAt = SipCallHandlerTest.NOW + 107
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            attributes = mutableMapOf()
            attributes!!["include-me"] = true
            payload = """
                        SIP/2.0 100 Trying
                        Via: SIP/2.0/UDP 176.9.119.117:5063;branch=z9hG4bK-2196628568-3926998818-1774583950-1258246515;received=176.9.119.117;rport=5063
                        From: <sip:123@176.9.119.117:5063;user=phone>;tag=3997885528-3926998818-1774583950-1258246515
                        To: <sip:321@116.203.55.139;user=phone>
                        Call-ID: 58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117
                        CSeq: 1 INVITE
                        Server: Asterisk PBX 13.29.1
                        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE
                        Supported: replaces,timer
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 503 Service Unavailable
        val FAILED_PACKET_3 = Packet().apply {
            createdAt = SipCallHandlerTest.NOW + 107 + 342
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 503 Service Unavailable
                        Via: SIP/2.0/UDP 176.9.119.117:5063;branch=z9hG4bK-2196628568-3926998818-1774583950-1258246515;received=176.9.119.117;rport=5063
                        From: <sip:123@176.9.119.117:5063;user=phone>;tag=3997885528-3926998818-1774583950-1258246515
                        To: <sip:321@116.203.55.139;user=phone>;tag=as50d13c8f
                        Call-ID: 58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117
                        CSeq: 1 INVITE
                        Server: Asterisk PBX 13.29.1
                        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE
                        Supported: replaces,timer
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }
    }

    @BeforeEach
    fun `Mock all`() {
        mockkConstructor(AttributesRegistry::class)
        every {
            anyConstructed<AttributesRegistry>().handle(any(), any())
        } just Runs
    }

    @Test
    fun `Deploy multiple 'SipTransactionHandler' instances`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipTransactionHandler::class, config = JsonObject(), instances = 4)
            },
            execute = {
                // Do nothing...
            },
            assert = {
                vertx.setPeriodic(100) {
                    val endpoints = vertx.eventBus().endpoints()
                    if (endpoints.size == 4) {
                        context.verify {
                            (0..3).forEach { i ->
                                assertTrue(endpoints.contains(SipTransactionHandler.PREFIX + "_$i"))
                            }
                        }
                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Aggregate failed OPTIONS transaction`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipTransactionHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("transaction", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("termination_timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_OPTIONS_1))
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_OPTIONS_2))
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    var (collection, document) = event.body()

                    document = document.getJsonObject("document")
                    context.verify {
                        assertTrue(collection.startsWith("sip_options_index"))
                        assertEquals(NOW, document.getLong("created_at"))
                        assertEquals(NOW + 25, document.getLong("terminated_at"))
                        assertEquals(PACKET_OPTIONS_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_OPTIONS_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_OPTIONS_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_OPTIONS_1.dstAddr.port, document.getInteger("dst_port"))
                        assertEquals("caller", document.getString("caller"))
                        assertEquals("failed", document.getString("state"))
                        assertEquals("503", document.getString("error_code"))
                        assertEquals("server", document.getString("error_type"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Aggregate valid MESSAGE transaction`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipTransactionHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("transaction", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("termination_timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_MESSAGE_1))
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_MESSAGE_2))
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    var (collection, document) = event.body()

                    document = document.getJsonObject("document")
                    context.verify {
                        assertTrue(collection.startsWith("sip_message_index"))
                        assertEquals(NOW, document.getLong("created_at"))
                        assertEquals(NOW + 25, document.getLong("terminated_at"))
                        assertEquals(PACKET_MESSAGE_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_MESSAGE_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_MESSAGE_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_MESSAGE_1.dstAddr.port, document.getInteger("dst_port"))
                        assertEquals("caller", document.getString("caller"))
                        assertEquals("succeed", document.getString("state"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Aggregate failed INVITE transaction`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipTransactionHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("transaction", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("termination_timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(FAILED_PACKET_1))
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(FAILED_PACKET_2))
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(FAILED_PACKET_3))
                }
            },
            assert = {
                vertx.eventBus().consumer<SipTransaction>(RoutesCE.sip + "_call_0") { event ->
                    val transaction = event.body()

                    context.verify {
                        assertEquals("INVITE", transaction.cseqMethod)
                        assertEquals(SipTransaction.FAILED, transaction.state)
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle transaction attributes`() {
        val prefixSlot = slot<String>()
        val attributesSlot = slot<Map<String, Any>>()
        every {
            anyConstructed<AttributesRegistry>().handle(capture(prefixSlot), capture(attributesSlot))
        } just Runs

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipTransactionHandler::class, config = JsonObject().apply {
                    put("attributes", JsonObject().apply {
                        put("record_ip_addresses", true)
                    })
                    put("sip", JsonObject().apply {
                        put("transaction", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("termination_timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_OPTIONS_1))
                    vertx.eventBus().localSend(SipTransactionHandler.PREFIX + "_0", handlerMessage(PACKET_OPTIONS_2))
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) {
                    context.verify {
                        assertEquals("sip", prefixSlot.captured)

                        val attributes = attributesSlot.captured
                        assertEquals(10, attributes.size)
                        assertEquals("OPTIONS", attributes["method"])
                        assertEquals("failed", attributes["state"])
                        assertEquals("127.0.0.1", attributes["src_addr"])
                        assertEquals("127.0.0.2", attributes["dst_addr"])
                        assertEquals("", attributes["caller"])
                        assertEquals("", attributes["callee"])
                        assertEquals("", attributes["call_id"])
                        assertEquals("503", attributes["error_code"])
                        assertEquals("server", attributes["error_type"])
                        assertNotNull(attributes["retransmits"])
                    }
                    context.completeNow()
                }
            }
        )
    }

    private fun handlerMessage(packet: Packet): Pair<Packet, SIPMessage> {
        val message = StringMsgParser().parseSIPMessage(packet.payload, true, false, null)
        return Pair(packet, message)
    }


    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}