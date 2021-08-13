/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

import gov.nist.javax.sip.parser.StringMsgParser
import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.commons.vertx.util.setPeriodic
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
import java.sql.Timestamp

@ExtendWith(MockKExtension::class)
class SipRegisterHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        // REGISTER
        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(NOW)
            srcAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            payload = """
                        REGISTER sip:192.168.10.5:5060 SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.123:55399;branch=z9hG4bK-d8754z-240d73239a6da57b-1---d8754z-;rport
                        Max-Forwards: 70
                        Contact: <sip:1010@192.168.10.123:55399;rinstance=13bf343a521442b5>
                        To: "1010"<sip:1010@192.168.10.5:5060>
                        From: "1010"<sip:1010@192.168.10.5:5060>;tag=bd285f07
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        CSeq: 143 REGISTER
                        Expires: 120
                        Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REGISTER, SUBSCRIBE, NOTIFY, REFER, INFO, MESSAGE
                        Supported: replaces
                        User-Agent: 3CXPhone 6.0.26523.0
                        Authorization: Digest username="1010",realm="asterisk",nonce="1589932693/afdc97bf8bb891c6c3c8072baeb2d3d6",uri="sip:192.168.10.5:5060",response="1337bca977875e158b7e1b96094521ee",cnonce="557bd23e12dc3db950db3c7e77aa91ad",nc=00000002,qop=auth,algorithm=md5,opaque="5b7b35877f215628"
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 401
        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(NOW + 5)
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            payload = """
                        SIP/2.0 401 Unauthorized
                        Via: SIP/2.0/UDP 192.168.10.123:55399;rport=55399;received=192.168.10.123;branch=z9hG4bK-d8754z-240d73239a6da57b-1---d8754z-
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        From: "1010" <sip:1010@192.168.10.5>;tag=bd285f07
                        To: "1010" <sip:1010@192.168.10.5>;tag=z9hG4bK-d8754z-240d73239a6da57b-1---d8754z-
                        CSeq: 143 REGISTER
                        WWW-Authenticate: Digest realm="asterisk",nonce="1589932801/8185e08b5190c16849be3d2cacdf342d8",opaque="3067a732021f5804",stale=true,algorithm=md5,qop="auth"
                        Server: FPBX-14.0.13.23(13.29.2)
                        Content-Length:  0
                    """.trimIndent().toByteArray()
        }

        // REGISTER
        val PACKET_3 = Packet().apply {
            timestamp = Timestamp(NOW + 5 + 10)
            srcAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            payload = """
                        REGISTER sip:192.168.10.5:5060 SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.123:55399;branch=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-;rport
                        Max-Forwards: 70
                        Contact: <sip:1010@192.168.10.123:55399;rinstance=13bf343a521442b5>
                        To: "1010"<sip:1010@192.168.10.5:5060>
                        From: "1010"<sip:1010@192.168.10.5:5060>;tag=bd285f07
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        CSeq: 144 REGISTER
                        Expires: 120
                        Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REGISTER, SUBSCRIBE, NOTIFY, REFER, INFO, MESSAGE
                        Supported: replaces
                        User-Agent: 3CXPhone 6.0.26523.0
                        Authorization: Digest username="1010",realm="asterisk",nonce="1589932801/8185e08b5190c16849be3d2cacdf342d8",uri="sip:192.168.10.5:5060",response="097123a1b44a287570f02dbb087ed690",cnonce="21b74f167c716d14bc7912330071b026",nc=00000001,qop=auth,algorithm=md5,opaque="3067a732021f5804"
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 200 Ok
        val PACKET_4 = Packet().apply {
            timestamp = Timestamp(NOW + 5 + 10 + 2)
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }

            attributes["include-me"] = true
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/UDP 192.168.10.123:55399;rport=55399;received=192.168.10.123;branch=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        From: "1010" <sip:1010@192.168.10.5>;tag=bd285f07
                        To: "1010" <sip:1010@192.168.10.5>;tag=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-
                        CSeq: 144 REGISTER
                        Date: Wed, 20 May 2020 00:00:01 GMT
                        Contact: <sip:1010@192.168.10.123:55399;rinstance=13bf343a521442b5>;expires=119
                        Expires: 120
                        Server: FPBX-14.0.13.23(13.29.2)
                        Content-Length:  0
                    """.trimIndent().toByteArray()
        }

        // 2nd REGISTER
        val PACKET_5 = Packet().apply {
            timestamp = Timestamp(NOW + 90015)
            srcAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            payload = """
                        REGISTER sip:192.168.10.5:5060 SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.123:55399;branch=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-;rport
                        Max-Forwards: 70
                        Contact: <sip:1010@192.168.10.123:55399;rinstance=13bf343a521442b5>
                        To: "1010"<sip:1010@192.168.10.5:5060>
                        From: "1010"<sip:1010@192.168.10.5:5060>;tag=bd285f07
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        CSeq: 144 REGISTER
                        Expires: 120
                        Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REGISTER, SUBSCRIBE, NOTIFY, REFER, INFO, MESSAGE
                        Supported: replaces
                        User-Agent: 3CXPhone 6.0.26523.0
                        Authorization: Digest username="1010",realm="asterisk",nonce="1589932801/8185e08b5190c16849be3d2cacdf342d8",uri="sip:192.168.10.5:5060",response="097123a1b44a287570f02dbb087ed690",cnonce="21b74f167c716d14bc7912330071b026",nc=00000001,qop=auth,algorithm=md5,opaque="3067a732021f5804"
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 2nd 200 Ok
        val PACKET_6 = Packet().apply {
            timestamp = Timestamp(NOW + 90020)
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }

            attributes["include-me"] = true
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/UDP 192.168.10.123:55399;rport=55399;received=192.168.10.123;branch=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-
                        Call-ID: ZDg3ZGU1ZTA1YjZkMThlNzEzOTA0Y2JkZmQ0YWU2ODU.
                        From: "1010" <sip:1010@192.168.10.5>;tag=bd285f07
                        To: "1010" <sip:1010@192.168.10.5>;tag=z9hG4bK-d8754z-ef77c05e05556d61-1---d8754z-
                        CSeq: 144 REGISTER
                        Date: Wed, 20 May 2020 00:00:01 GMT
                        Contact: <sip:1010@192.168.10.123:55399;rinstance=13bf343a521442b5>;expires=119
                        Expires: 120
                        Server: FPBX-14.0.13.23(13.29.2)
                        Content-Length:  0
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
    fun `Aggregate and check 'registered' session`() {
        val transaction401 = SipTransaction().apply {
            addPacket(PACKET_1)
            addPacket(PACKET_2)
        }

        val transaction200 = SipTransaction().apply {
            addPacket(PACKET_3)
            addPacket(PACKET_4)
        }
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 200)
                            put("duration-timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setTimer(400) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction401)
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction200)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))

                        document.getJsonObject("\$setOnInsert").apply {
                            assertEquals(PACKET_1.timestamp.time, getLong("created_at"))
                            assertEquals(PACKET_1.srcAddr.addr, getString("src_addr"))
                            assertEquals(PACKET_1.srcAddr.port, getInteger("src_port"))
                            assertEquals(PACKET_1.dstAddr.addr, getString("dst_addr"))
                            assertEquals(PACKET_1.dstAddr.port, getInteger("dst_port"))
                            assertNotNull(getString("call_id"))
                        }

                        document.getJsonObject("\$set").apply {
                            assertEquals("1010", getString("caller"))
                            assertEquals("1010", getString("callee"))
                            assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                            assertEquals(PACKET_3.timestamp.time + 120000, getLong("terminated_at"))
                            assertEquals(PACKET_3.timestamp.time + 120000 - PACKET_1.timestamp.time, getLong("duration"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate 'overlapped_interval' and 'overlapped_fraction'`() {
        val transaction1 = SipTransaction().apply {
            addPacket(PACKET_3)
            addPacket(PACKET_4)
        }

        val transaction2 = SipTransaction().apply {
            addPacket(PACKET_5)
            addPacket(PACKET_6)
        }
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 200)
                            put("duration-timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setTimer(400) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction1)
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction2)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))

                        document.getJsonObject("\$setOnInsert").apply {
                            assertEquals(PACKET_3.timestamp.time, getLong("created_at"))
                            assertEquals(PACKET_3.srcAddr.addr, getString("src_addr"))
                            assertEquals(PACKET_3.srcAddr.port, getInteger("src_port"))
                            assertEquals(PACKET_3.dstAddr.addr, getString("dst_addr"))
                            assertEquals(PACKET_3.dstAddr.port, getInteger("dst_port"))
                            assertNotNull(getString("call_id"))
                        }

                        document.getJsonObject("\$set").apply {
                            assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                            assertEquals(PACKET_5.timestamp.time + 120000, getLong("terminated_at"))
                            assertEquals(PACKET_5.timestamp.time + 120000 - PACKET_3.timestamp.time, getLong("duration"))
                            assertEquals(30000L, getLong("overlapped_interval"))
                            assertEquals(0.25, getDouble("overlapped_fraction"))
                            assertEquals(2, getInteger("transactions"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate 'synced' flag for Registration SipSession`() {
        val transaction1 = SipTransaction().apply {
            addPacket(PACKET_3)
            addPacket(PACKET_4)
        }

        val transaction2 = SipTransaction().apply {
            addPacket(PACKET_5)
            addPacket(PACKET_6)
        }
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 1000)
                            put("duration-timeout", 5000)
                            put("update-period", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setTimer(400) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction1)
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction2)
                }
            },
            assert = {
                var createdAt: Long? = null
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")
                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))

                        if (createdAt == null) {
                            // Assert first write to DB for Registration SipSession
                            createdAt = System.currentTimeMillis()
                            document.apply {
                                assertEquals(PACKET_3.timestamp.time, getLong("created_at"))
                                assertEquals(PACKET_3.srcAddr.addr, getString("src_addr"))
                                assertEquals(PACKET_3.srcAddr.port, getInteger("src_port"))
                                assertEquals(PACKET_3.dstAddr.addr, getString("dst_addr"))
                                assertEquals(PACKET_3.dstAddr.port, getInteger("dst_port"))
                                assertNotNull(getString("call_id"))
                                assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                                assertEquals(PACKET_5.timestamp.time + 120000, getLong("terminated_at"))
                                assertNull(getLong("duration"))
                                assertEquals(30000L, getLong("overlapped_interval"))
                                assertEquals(0.25, getDouble("overlapped_fraction"))
                            }
                        } else {
                            // Ensure no writes were performed without session updates in at least 3 update periods
                            assertTrue(System.currentTimeMillis() - createdAt!! > 700)

                            // Assert update session in DB
                            document.getJsonObject("\$setOnInsert").apply {
                                assertEquals(PACKET_3.timestamp.time, getLong("created_at"))
                                assertEquals(PACKET_3.srcAddr.addr, getString("src_addr"))
                                assertEquals(PACKET_3.srcAddr.port, getInteger("src_port"))
                                assertEquals(PACKET_3.dstAddr.addr, getString("dst_addr"))
                                assertEquals(PACKET_3.dstAddr.port, getInteger("dst_port"))
                                assertNotNull(getString("call_id"))
                            }

                            document.getJsonObject("\$set").apply {
                                assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                                assertEquals(PACKET_5.timestamp.time + 120000, getLong("terminated_at"))
                                assertEquals(PACKET_5.timestamp.time + 120000 - PACKET_3.timestamp.time, getLong("duration"))
                                assertEquals(30000L, getLong("overlapped_interval"))
                                assertEquals(0.25, getDouble("overlapped_fraction"))
                            }
                            context.completeNow()
                        }
                    }

                }
            }
        )
    }

    @Test
    fun `Aggregate and check 'unauthorized' session`() {
        val transaction401 = SipTransaction().apply {
            addPacket(PACKET_1)
            addPacket(PACKET_2)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 200)
                            put("duration-timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 10000) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction401)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))

                        document.apply {
                            assertEquals(PACKET_1.timestamp.time, getLong("created_at"))
                            assertEquals(PACKET_1.srcAddr.addr, getString("src_addr"))
                            assertEquals(PACKET_1.srcAddr.port, getInteger("src_port"))
                            assertEquals(PACKET_1.dstAddr.addr, getString("dst_addr"))
                            assertEquals(PACKET_1.dstAddr.port, getInteger("dst_port"))
                            assertNotNull(getString("call_id"))

                            assertEquals("1010", getString("caller"))
                            assertEquals("1010", getString("callee"))
                            assertEquals(SipRegisterHandler.UNAUTHORIZED, getString("state"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle registration attributes`() {
        val prefixSlot = slot<String>()
        val attributesSlot = slot<Map<String, Any>>()
        every {
            anyConstructed<AttributesRegistry>().handle(capture(prefixSlot), capture(attributesSlot))
        } just Runs

        val transaction401 = SipTransaction().apply {
            addPacket(PACKET_1)
            addPacket(PACKET_2)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 200)
                            put("duration-timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 10000) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction401)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) {
                    context.verify {
                        assertEquals("sip", prefixSlot.captured)

                        val attributes = attributesSlot.captured
                        assertEquals(8, attributes.size)
                        assertEquals("unauthorized", attributes["state"])
                        assertEquals("", attributes["src_addr"])
                        assertEquals("", attributes["dst_addr"])
                        assertEquals("", attributes["caller"])
                        assertEquals("", attributes["callee"])
                        assertEquals("", attributes["call_id"])
                        assertEquals(1, attributes["transactions"])
                        assertNotNull(attributes["retransmits"])
                    }
                    context.completeNow()
                }
            }
        )
    }

    private fun SipTransaction.addPacket(packet: Packet) {
        val message = StringMsgParser().parseSIPMessage(packet.payload, true, false, null)
        addMessage(packet, message)
    }


    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}
