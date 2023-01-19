/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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
class SipRegisterHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        // REGISTER
        val PACKET_1 = Packet().apply {
            createdAt = NOW
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
            createdAt = NOW + 5
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
            createdAt = NOW + 5 + 10
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
            createdAt = NOW + 5 + 10 + 2
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            attributes = mutableMapOf()
            attributes!!["include-me"] = true
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
            createdAt = NOW + 90015
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
            createdAt = NOW + 90020
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            attributes = mutableMapOf()
            attributes!!["include-me"] = true
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

        // REGISTER
        val PACKET_7 = Packet().apply {
            createdAt = NOW
            srcAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            payload = """
                        REGISTER sip:192.168.10.5:5060;transport=udp SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.123:5060;rport;branch=z9hG4bK2rB6X308rv4vg
                        Max-Forwards: 70
                        From: <sip:26101986@192.168.10.5>;tag=N6242jtac3K1H
                        To: <sip:26101986@192.168.10.5>
                        Call-ID: 060de43c-04a7-11ec-8721-cdec45fe8260
                        CSeq: 41060268 REGISTER
                        Contact: <sip:26101986@192.168.10.123:5060;transport=udp;gw=d8f665c2-b717-4c9d-a1fe-9528e240b387>
                        Expires: 0
                        User-Agent: FreeSWITCH
                        Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
                        Supported: timer, path, replaces
                        Authorization: Digest username="26101986", realm="192.168.10.5", nonce="02305a0082903a108000000c290b59cf@vs.krsk.info", algorithm=MD5, uri="sip:192.168.10.5:5060;transport=udp", response="62d03a51b79827db20ef40ff02c3eb15"
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 403 Forbidden
        val PACKET_8 = Packet().apply {
            createdAt = NOW + 20
            srcAddr = Address().apply {
                addr = "192.168.10.5"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "192.168.10.123"
                port = 5060
            }
            attributes = mutableMapOf()
            attributes!!["include-me"] = true
            payload = """
                        SIP/2.0 403 Forbidden
                        Via: SIP/2.0/UDP 192.168.10.123:5060;rport;branch=z9hG4bK2rB6X308rv4vg
                        From: <sip:26101986@192.168.10.5>;tag=N6242jtac3K1H
                        To: <sip:26101986@192.168.10.5>
                        Call-ID: 060de43c-04a7-11ec-8721-cdec45fe8260
                        CSeq: 41060268 REGISTER
                        Contact: <sip:26101986@192.168.10.123:5060;transport=udp;gw=d8f665c2-b717-4c9d-a1fe-9528e240b387>
                        Expires: 0
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
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 5000)
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

                        assertEquals(PACKET_1.createdAt, document.getLong("created_at"))
                        assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))
                        assertNotNull(document.getString("call_id"))

                        assertEquals("1010", document.getString("caller"))
                        assertEquals("1010", document.getString("callee"))
                        assertEquals(SipRegisterHandler.REGISTERED, document.getString("state"))
                        assertEquals(PACKET_3.createdAt + 120000, document.getLong("terminated_at"))
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
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 5000)
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

                        assertEquals(PACKET_3.createdAt, document.getLong("created_at"))
                        assertEquals(PACKET_3.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_3.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_3.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_3.dstAddr.port, document.getInteger("dst_port"))
                        assertNotNull(document.getString("call_id"))

                        assertEquals(SipRegisterHandler.REGISTERED, document.getString("state"))
                        assertEquals(PACKET_5.createdAt + 120000, document.getLong("terminated_at"))
                        assertEquals(30000L, document.getLong("overlapped_interval"))
                        assertEquals(0.25, document.getDouble("overlapped_fraction"))
                        assertEquals(2, document.getInteger("transactions"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate sync for Registration SipSession`() {
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
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 1000)
                            put("duration_timeout", 200000)
                            put("update_period", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setTimer(400) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction1)

                    vertx.setTimer(800) {
                        vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction2)
                    }
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
                                assertEquals(PACKET_3.createdAt, getLong("created_at"))
                                assertEquals(PACKET_3.srcAddr.addr, getString("src_addr"))
                                assertEquals(PACKET_3.srcAddr.port, getInteger("src_port"))
                                assertEquals(PACKET_3.dstAddr.addr, getString("dst_addr"))
                                assertEquals(PACKET_3.dstAddr.port, getInteger("dst_port"))
                                assertNotNull(getString("call_id"))
                                assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                                assertEquals(PACKET_3.createdAt + 120000, getLong("terminated_at"))
                                assertNull(getLong("duration"))
                            }
                        } else {
                            // Ensure no writes were performed without session updates in at least 3 update periods
                            assertTrue(System.currentTimeMillis() - createdAt!! > 700)

                            // Assert update session in DB
                            document.getJsonObject("\$setOnInsert").apply {
                                assertEquals(PACKET_3.createdAt, getLong("created_at"))
                                assertEquals(PACKET_3.srcAddr.addr, getString("src_addr"))
                                assertEquals(PACKET_3.srcAddr.port, getInteger("src_port"))
                                assertEquals(PACKET_3.dstAddr.addr, getString("dst_addr"))
                                assertEquals(PACKET_3.dstAddr.port, getInteger("dst_port"))
                                assertNotNull(getString("call_id"))
                            }

                            document.getJsonObject("\$set").apply {
                                assertEquals(SipRegisterHandler.REGISTERED, getString("state"))
                                assertEquals(PACKET_5.createdAt + 120000, getLong("terminated_at"))
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
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 3000) {
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
                            assertEquals(PACKET_1.createdAt, getLong("created_at"))
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
    fun `Aggregate and check 'failed' registration`() {
        val transaction403 = SipTransaction().apply {
            addPacket(PACKET_7)
            addPacket(PACKET_8)
        }

        runTest(
            deploy = {
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 3000) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction403)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))

                        document.apply {
                            assertEquals(PACKET_7.createdAt, getLong("created_at"))
                            assertEquals(PACKET_7.srcAddr.addr, getString("src_addr"))
                            assertEquals(PACKET_7.srcAddr.port, getInteger("src_port"))
                            assertEquals(PACKET_7.dstAddr.addr, getString("dst_addr"))
                            assertEquals(PACKET_7.dstAddr.port, getInteger("dst_port"))
                            assertNotNull(getString("call_id"))

                            assertEquals("26101986", getString("caller"))
                            assertEquals("26101986", getString("callee"))
                            assertEquals(SipRegisterHandler.FAILED, getString("state"))

                            assertEquals("403", getString("error_code"))
                            assertEquals("client", getString("error_type"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Aggregate 'failed' registration with replaced 'caller' and 'callee'`() {
        val transaction403 = SipTransaction().apply {
            addPacket(PACKET_7)
            addPacket(PACKET_8)
            attributes[Attributes.caller] = "replaced"
            attributes[Attributes.callee] = "replaced"
        }

        runTest(
            deploy = {
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 3000) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction403)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_register_index_"))
                        assertEquals("replaced", document.getString("caller"))
                        assertEquals("replaced", document.getString("callee"))
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
                mockMongoCollectionManager()
                vertx.deployTestVerticle(SipRegisterHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("register", JsonObject().apply {
                            put("expiration_delay", 100)
                            put("aggregation_timeout", 200)
                            put("duration_timeout", 200)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 3000) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_register_0", transaction401)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) {
                    context.verify {
                        assertEquals("sip", prefixSlot.captured)

                        val attributes = attributesSlot.captured
                        assertEquals(9, attributes.size)
                        assertEquals("REGISTER", attributes["method"])
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

    private fun mockMongoCollectionManager() {
        vertx.eventBus().localConsumer<String>(RoutesCE.mongo_collection_hint) { event ->
            val prefix = event.body()
            context.verify {
                assertEquals("sip_register_index", prefix)
            }

            event.reply(JsonObject().apply {
                put("created_at", 1)
            })
        }
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
