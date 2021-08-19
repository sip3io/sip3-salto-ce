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
import io.sip3.commons.vertx.util.endpoints
import io.sip3.commons.vertx.util.localSend
import io.sip3.commons.vertx.util.setPeriodic
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
import java.sql.Timestamp

@ExtendWith(MockKExtension::class)
class SipCallHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        // INVITE
        val UNKNOWN_PACKET_1 = Packet().apply {
            timestamp = Timestamp(NOW)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            payload = """
                        INVITE sip:1951290411854098895@demo.sip3.io SIP/2.0
                        Via: SIP/2.0/UDP demo.sip3.io:60280;branch=z9hG4bK.7fsqeOsQU;rport
                        Call-ID: b507406aeb858e77b60b00c20cd96128
                        From: <sip:6469362828735520143@demo.sip3.io>;tag=C61wMzZ48
                        To: <sip:1951290411854098895@demo.sip3.io>
                        Contact: <sip:6469362828735520143@demo.sip3.io:46873;transport=udp>;transport=udp;expires=10;+sip.instance="<urn:uuid:28c9c934-5395-40e1-b992-5a77c021dc1c>"
                        User-Agent: DemoApplication_Android/5.1.1_OS_3.2.0 (belle-sip/1.6.3)
                        CSeq: 20 INVITE
                        Max-Forwards: 70
                        Content-Type: application/sdp
                        Content-Length: 382

                        Content-Type: application/sdp

                        v=0
                        o=- 23082015 3140674329 IN IP4 10.10.10.10
                        s=media-server
                        c=IN IP4 10.10.10.10
                        t=0 0
                        a=rtcp-xr:rcvr-rtt=all:10000 stat-summary=loss,dup,jitt,TTL voip-metrics
                        m=audio 7078 RTP/AVP 96 8 3 101 97
                        a=rtpmap:96 opus/48000/2
                        a=fmtp:96 useinbandfec=1
                        a=rtpmap:101 telephone-event/48000
                        a=rtpmap:97 telephone-event/8000
                        a=rtcp-fb:* ccm tmmbr
                        
                    """.trimIndent().toByteArray()
        }

        // INVITE
        val FAILED_PACKET_1 = Packet().apply {
            timestamp = Timestamp(NOW)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            attributes[Attributes.caller] = "caller"
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
            timestamp = Timestamp(NOW + 107)
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            attributes["include-me"] = true
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
            timestamp = Timestamp(NOW + 107 + 342)
            srcAddr = Address().apply {
                addr = "127.0.0.3"
                host = "Test"
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

        // INVITE
        val ANSWERED_PACKET_1 = Packet().apply {
            timestamp = Timestamp(NOW)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            payload = """
                        INVITE sip:558552290881@groot.clearcaptions.com:35060 SIP/2.0
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        CSeq: 1 INVITE
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>
                        Via: SIP/2.0/TCP 192.168.0.21:5060;branch=z9hG4bK36186c94b643be059abfce1e696697ff
                        Max-Forwards: 70
                        Expires: 120
                        Contact: "4801370F02092417" <sip:4801370F02092417@192.168.0.21:5060>
                        Content-Type: application/sdp
                        Content-Length: 240

                        Content-Type: application/sdp

                        v=0
                        o=4801370F02092417 0 0 IN IP4 192.168.0.21
                        s=call
                        c=IN IP4 192.168.0.21
                        t=0 0
                        m=audio 5071 RTP/AVP 0 3 8
                        a=rtpmap:0 PCMU/8000
                        a=rtpmap:3 GSM/8000
                        a=rtpmap:8 PCMA/8000
                        a=sendrecv
                        a=rtcp:5072
                        
                    """.trimIndent().toByteArray()
        }

        // 100 Trying
        val ANSWERED_PACKET_2 = Packet().apply {
            timestamp = Timestamp(NOW + 2)
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 100 trying -- your call is important to us
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        CSeq: 1 INVITE
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>
                        Via: SIP/2.0/TCP 192.168.0.21:5060;branch=z9hG4bK36186c94b643be059abfce1e696697ff;rport=51896;received=97.113.97.122
                        Server: kamailio (5.2.3 (x86_64/linux))
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // 200 OK
        val ANSWERED_PACKET_3 = Packet().apply {
            timestamp = Timestamp(NOW + 2 + 23)
            srcAddr = Address().apply {
                addr = "127.0.0.3"
                host = "Test"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/TCP 192.168.0.21:5060;rport=51896;received=97.113.97.122;branch=z9hG4bK36186c94b643be059abfce1e696697ff
                        Record-Route: <sip:141.193.26.211;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>,<sip:141.193.26.211:35060;transport=tcp;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>;tag=as36ddbbca
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        CSeq: 1 INVITE
                        Server: Asterisk PBX 15.3.0
                        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE
                        Supported: replaces,timer
                        Session-Expires: 28800;refresher=uas
                        Contact: <sip:558552290881@141.193.26.74:5060>
                        Content-Type: application/sdp
                        Content-Length: 265

                        Content-Type: application/sdp

                        v=0
                        o=root 745656684 745656684 IN IP4 141.193.26.74
                        s=Asterisk PBX 15.3.0
                        c=IN IP4 141.193.26.74
                        t=0 0
                        m=audio 15192 RTP/AVP 0 8 3
                        a=rtpmap:0 PCMU/8000
                        a=rtpmap:8 PCMA/8000
                        a=rtpmap:3 GSM/8000
                        a=maxptime:150
                        a=sendrecv
                        
                    """.trimIndent().toByteArray()
        }

        // ACK
        val ANSWERED_PACKET_4 = Packet().apply {
            timestamp = Timestamp(NOW + 2 + 23 + 128)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            payload = """
                        ACK sip:558552290881@141.193.26.74:5060 SIP/2.0
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        CSeq: 1 ACK
                        Via: SIP/2.0/TCP 192.168.0.21:5060;rport=51896;received=97.113.97.122;branch=z9hG4bKb55a3a2148a14dfd9322c9571ae1ea21
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>;tag=as36ddbbca
                        Max-Forwards: 70
                        Route: <sip:141.193.26.211:35060;transport=tcp;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>,<sip:141.193.26.211;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>
                        Content-Length: 0
                    """.trimIndent().toByteArray()
        }

        // BYE
        val ANSWERED_PACKET_5 = Packet().apply {
            timestamp = Timestamp(NOW + 2 + 23 + 128 + 221)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                host = "Test"
                port = 5061
            }
            payload = """
                        BYE sip:558552290881@141.193.26.74:5060 SIP/2.0
                        Via: SIP/2.0/TCP 192.168.0.21:5060;branch=z9hG4bK5348c5f5be6f4fd399083d0f61ab0f8f
                        CSeq: 2 BYE
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>;tag=as36ddbbca
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE
                        Supported: replaces,timer
                        Session-Expires: 28800;refresher=uas
                        Max-Forwards: 70
                        Route: <sip:141.193.26.211:35060;transport=tcp;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>,<sip:141.193.26.211;r2=on;lr;ftag=373436342e393734323132363431313936;did=2fd.3041;nat=yes>
                        Content-Length: 0                        
                    """.trimIndent().toByteArray()
        }

        // 200 OK
        val ANSWERED_PACKET_6 = Packet().apply {
            timestamp = Timestamp(NOW + 2 + 23 + 128 + 221 + 1)
            srcAddr = Address().apply {
                addr = "127.0.0.3"
                host = "Test"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/TCP 192.168.0.21:5060;rport=51896;received=97.113.97.122;branch=z9hG4bK5348c5f5be6f4fd399083d0f61ab0f8f
                        From: "4801370F02092417" <sip:4801370F02092417@192.168.0.21>;tag=373436342e393734323132363431313936
                        To: "558552290881" <sip:558552290881@groot.clearcaptions.com:35060>;tag=as36ddbbca
                        Call-ID: 0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21
                        CSeq: 2 BYE
                        Server: Asterisk PBX 15.3.0
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
    fun `Deploy multiple 'SipCallHandler' instances`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject(), instances = 4)
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
                                assertTrue(endpoints.contains(SipCallHandler.PREFIX + "_$i"))
                            }
                        }
                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Aggregate and check 'unknown' session`() {
        val transaction = SipTransaction().apply {
            addPacket(UNKNOWN_PACKET_1)
        }
        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("aggregation-timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", transaction)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_call_index_"))
                        assertEquals(NOW, document.getLong("created_at"))
                        assertEquals(SipTransactionTest.PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(SipTransactionTest.PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(SipTransactionTest.PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(SipTransactionTest.PACKET_1.dstAddr.port, document.getInteger("dst_port"))
                        assertEquals("b507406aeb858e77b60b00c20cd96128", document.getString("call_id"))
                        assertEquals("6469362828735520143", document.getString("caller"))
                        assertEquals("1951290411854098895", document.getString("callee"))
                        assertEquals("unknown", document.getString("state"))
                        assertEquals(1, document.getInteger("transactions"))
                        assertEquals(0, document.getInteger("retransmits"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Aggregate and check 'failed' session`() {
        val transaction = SipTransaction().apply {
            addPacket(FAILED_PACKET_1)
            addPacket(FAILED_PACKET_2)
            addPacket(FAILED_PACKET_3)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("termination-timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 200) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", transaction)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_call_index_"))
                        assertEquals(NOW, document.getLong("created_at"))
                        assertEquals(NOW + 107 + 342, document.getLong("terminated_at"))
                        assertEquals(FAILED_PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(FAILED_PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(FAILED_PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(FAILED_PACKET_1.dstAddr.port, document.getInteger("dst_port"))
                        assertEquals(FAILED_PACKET_1.dstAddr.host, document.getString("dst_host"))
                        assertEquals("caller", document.getString("caller"))
                        assertEquals("321", document.getString("callee"))
                        assertEquals("failed", document.getString("state"))
                        assertEquals(107, document.getLong("trying_delay"))
                        assertEquals(503, document.getLong("error_code"))
                        assertEquals("server", document.getString("error_type"))
                        assertEquals(1, document.getInteger("transactions"))
                        assertEquals(0, document.getInteger("retransmits"))
                        assertEquals(true, document.getBoolean("include-me"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Aggregate and check 'answered' session`() {
        val inviteTransaction = SipTransaction().apply {
            addPacket(ANSWERED_PACKET_1)
            addPacket(ANSWERED_PACKET_2)
            addPacket(ANSWERED_PACKET_3)
            addPacket(ANSWERED_PACKET_3)
        }
        val byeTransaction = SipTransaction().apply {
            addPacket(ANSWERED_PACKET_5)
            addPacket(ANSWERED_PACKET_6)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("termination-timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 100) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", inviteTransaction)
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", byeTransaction)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val filter = operation.getJsonObject("filter")
                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("sip_call_index_"))

                        assertEquals(NOW, filter.getLong("created_at"))
                        assertEquals(ANSWERED_PACKET_1.srcAddr.addr, filter.getString("src_addr"))
                        assertNull(filter.getString("src_host"))
                        assertNull(filter.getString("dst_addr"))
                        assertEquals(ANSWERED_PACKET_1.dstAddr.host, filter.getString("dst_host"))
                        assertEquals("0a778dd44d9cc00e16ac97a623d5202a@192.168.0.21", filter.getString("call_id"))


                        val setOnInsert = document.getJsonObject("\$setOnInsert")
                        assertEquals(NOW, setOnInsert.getLong("created_at"))
                        assertEquals(ANSWERED_PACKET_1.srcAddr.addr, setOnInsert.getString("src_addr"))
                        assertEquals(ANSWERED_PACKET_1.srcAddr.port, setOnInsert.getInteger("src_port"))
                        assertEquals(ANSWERED_PACKET_1.dstAddr.addr, setOnInsert.getString("dst_addr"))
                        assertEquals(ANSWERED_PACKET_1.dstAddr.port, setOnInsert.getInteger("dst_port"))
                        assertEquals("4801370F02092417", setOnInsert.getString("caller"))
                        assertEquals("558552290881", setOnInsert.getString("callee"))

                        val set = document.getJsonObject("\$set")
                        assertEquals("answered", set.getString("state"))
                        assertEquals(2, set.getLong("trying_delay"))
                        assertEquals(25, set.getLong("establish_time"))
                        set.getLong("terminated_at")?.let { terminatedAt ->
                            assertEquals(NOW + 2 + 23 + 128 + 221 + 1, terminatedAt)
                            assertEquals("caller", set.getString("terminated_by"))
                            assertEquals(1, set.getLong("disconnect_time"))
                            assertEquals(2, set.getInteger("transactions"))
                            assertEquals(1, set.getInteger("retransmits"))
                            context.completeNow()
                        }
                    }
                }
            }
        )
    }

    @Test
    fun `Call 'sip_call_udf'`() {
        val transaction = SipTransaction().apply {
            addPacket(FAILED_PACKET_1)
            addPacket(FAILED_PACKET_2)
            addPacket(FAILED_PACKET_3)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("termination-timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 200) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", transaction)
                }
            },
            assert = {
                vertx.eventBus().localConsumer<Map<String, Any>>(RoutesCE.sip_call_udf) { event ->
                    val session = event.body()

                    context.verify {
                        assertEquals("127.0.0.1", session["src_addr"])
                        assertEquals(5060, session["src_port"])
                        assertNull(session["src_host"])

                        assertEquals("127.0.0.2", session["dst_addr"])
                        assertEquals(5061, session["dst_port"])
                        assertEquals("Test", session["dst_host"])

                        val payload = session["payload"] as Map<String, Any>

                        assertEquals(NOW, payload["created_at"])
                        assertEquals(NOW + 107 + 342, payload["terminated_at"])

                        assertEquals("failed", payload["state"])
                        assertEquals("caller", payload["caller"])
                        assertEquals("321", payload["callee"])
                        assertEquals("58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117", payload["call_id"])
                        assertEquals(107L, payload["trying_delay"])
                        assertEquals(503, payload["error_code"])
                        assertEquals("server", payload["error_type"])
                        assertEquals(1, payload["transactions"])
                        assertEquals(0, payload["retransmits"])
                        assertEquals(true, payload["include-me"])
                    }
                    context.completeNow()

                    event.reply(true)
                }
            }
        )
    }

    @Test
    fun `Handle session attributes`() {
        val prefixSlot = slot<String>()
        val attributesSlot = slot<Map<String, Any>>()
        every {
            anyConstructed<AttributesRegistry>().handle(capture(prefixSlot), capture(attributesSlot))
        } just Runs

        val transaction = SipTransaction().apply {
            addPacket(FAILED_PACKET_1)
            addPacket(FAILED_PACKET_2)
            addPacket(FAILED_PACKET_3)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("attributes", JsonObject().apply {
                        put("record-call-users", true)
                    })
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("termination-timeout", 100)
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 200) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", transaction)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) {
                    context.verify {
                        assertEquals("sip", prefixSlot.captured)

                        val attributes = attributesSlot.captured
                        assertEquals(13, attributes.size)
                        assertEquals("failed", attributes["state"])
                        assertEquals("", attributes["src_addr"])
                        assertEquals("", attributes["dst_addr"])
                        assertEquals("Test", attributes["dst_host"])
                        assertEquals("caller", attributes["caller"])
                        assertEquals("321", attributes["callee"])
                        assertEquals("", attributes["call_id"])
                        assertEquals(107L, attributes["trying_delay"])
                        assertEquals(503, attributes["error_code"])
                        assertEquals("server", attributes["error_type"])
                        assertEquals(1, attributes["transactions"])
                        assertNotNull(attributes["retransmits"])
                        assertEquals(true, attributes["include-me"])
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Send correlation event`() {
        val transaction = SipTransaction().apply {
            addPacket(FAILED_PACKET_1)
            addPacket(FAILED_PACKET_2)
            addPacket(FAILED_PACKET_3)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(SipCallHandler::class, config = JsonObject().apply {
                    put("sip", JsonObject().apply {
                        put("call", JsonObject().apply {
                            put("expiration-delay", 100)
                            put("termination-timeout", 100)
                            put("correlation", JsonObject().apply {
                                put("role", "reporter")
                            })
                        })
                    })
                })
            },
            execute = {
                vertx.setPeriodic(200, 200) {
                    vertx.eventBus().localSend(RoutesCE.sip + "_call_0", transaction)
                }
            },
            assert = {
                vertx.eventBus().consumer<JsonObject>(RoutesCE.sip + "_call_correlation") { event ->
                    val correlationEvent = event.body()
                    context.verify {
                        assertEquals(NOW, correlationEvent.getLong("created_at"))
                        assertEquals(NOW + 107 + 342, correlationEvent.getLong("terminated_at"))
                        assertEquals(FAILED_PACKET_1.srcAddr.addr, correlationEvent.getString("src_host"))
                        assertEquals(FAILED_PACKET_1.dstAddr.host, correlationEvent.getString("dst_host"))
                        assertEquals("caller", correlationEvent.getString("caller"))
                        assertEquals("321", correlationEvent.getString("callee"))
                        assertEquals("58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117", correlationEvent.getString("call_id"))
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