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

package io.sip3.salto.ce.media

import gov.nist.javax.sip.parser.StringMsgParser
import io.sip3.commons.domain.media.Codec
import io.sip3.commons.domain.media.MediaAddress
import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localReply
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.sip.SipTransaction
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaManagerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        val CODEC = Codec().apply {
            name = "PCMA"
            payloadTypes = listOf(8)
            clockRate = 8000
            ie = 0.0F
            bpl = 4.3F
        }

        // Valid SIP Message (INVITE)
        val PACKET_REQUEST_1 = Packet().apply {
            createdAt = NOW
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
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
                        Call-ID: some-callId
                        CSeq: 1 INVITE
                        Contact: <sip:signode-82-gxp92pqazkbzz@10.177.131.211:6333;transport=udp>
                        Allow: INVITE,ACK,CANCEL,BYE,INFO,REFER,SUBSCRIBE,NOTIFY
                        Allow-Events: keep-alive
                        Supported: timer
                        Session-Expires: 7200
                        Expires: 300
                        Min-SE: 900
                        Max-Forwards: 63
                        User-Agent: ITLCS 3.8.1
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

        // Valid SIP Message response with SDP
        val PACKET_RESPONSE_1 = Packet().apply {
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
                        Supported: 100rel,precondition,timer
                        Content-Type: application/sdp
                        Content-Disposition: session;handling=required
                        Allow: ACK,BYE,CANCEL,INFO,INVITE,OPTIONS,PRACK
                        Contact: <sip:000155917690@10.177.141.80:5060>;expires=180
                        From: <sip:000260971282@demo.sip3.io>;tag=82-2zyzysoabqjb3
                        To: <sip:000155917690@demo.sip3.io:5060>;tag=56B5324631353641B4C0D0A8
                        Call-ID: some-callId
                        CSeq: 1 INVITE
                        Via: SIP/2.0/UDP 10.177.131.211:6333;branch=z9hG4bKmqffet30b03pp5mv5jj0.1;received=10.177.131.211
                        Content-Length: 144
                
                        v=0
                        o=- 0 0 IN IP4 10.177.116.41
                        s=-
                        c=IN IP4 10.177.116.41
                        t=0 0
                        m=audio 36046 RTP/AVP 8
                        b=AS:80
                        a=rtpmap:8 PCMA/8000
                        a=ptime:20
                        a=maxptime:20
                
                        """.trimIndent().toByteArray()
        }

        val SRC_ADDR = Address().apply {
            addr = "10.10.10.10"
            port = 10500
            host = "SomeHost"
        }

        val DST_ADDR = Address().apply {
            addr = "10.20.20.20"
            port = 20500
        }

        // SdpSession
        val SDP_SESSION = SdpSession().apply {
            src = MediaAddress().apply {
                addr = SRC_ADDR.addr
                rtpPort = SRC_ADDR.port
                rtcpPort = SRC_ADDR.port + 1
            }
            dst = MediaAddress().apply {
                addr = DST_ADDR.addr
                rtpPort = DST_ADDR.port
                rtcpPort = DST_ADDR.port + 1
            }

            codecs = mutableListOf(Codec().apply {
                name = "PCMU"
                payloadTypes = listOf(0)
                clockRate = 8000
                bpl = 4.3F
                ie = 0F
            })
        }
    }

    @Test
    fun `Validate MediaControl publish`() {
        val transaction = SipTransaction().apply {
            addPacket(PACKET_REQUEST_1)
            addPacket(PACKET_RESPONSE_1)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaManager::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_sdp", transaction)
            },
            assert = {
                vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.sdp + "_session") { event ->
                    event.localReply(SDP_SESSION)
                }

                vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
                    val mediaControl = event.body()
                    context.verify {
                        assertEquals(transaction.createdAt, mediaControl.timestamp)
                        assertEquals(transaction.callId, mediaControl.callId)
                        assertEquals(SDP_SESSION, mediaControl.sdpSession)

                        assertNull(mediaControl.recording)
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate MediaControl publish with 'caller' in attributes`() {
        val transaction = SipTransaction().apply {
            addPacket(PACKET_REQUEST_1)
            addPacket(PACKET_RESPONSE_1)
            attributes["caller"] = "replaced"
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaManager::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_sdp", transaction)
            },
            assert = {
                vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.sdp + "_session") { event ->
                    event.localReply(SDP_SESSION)
                }

                vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
                    val mediaControl = event.body()
                    context.verify {
                        assertEquals(transaction.createdAt, mediaControl.timestamp)
                        assertEquals(transaction.callId, mediaControl.callId)
                        assertEquals(SDP_SESSION, mediaControl.sdpSession)

                        assertEquals("replaced", mediaControl.caller)
                        assertEquals(transaction.callee, mediaControl.callee)

                        assertNull(mediaControl.recording)
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate MediaControl publish with recording`() {
        val transaction = SipTransaction().apply {
            addPacket(PACKET_REQUEST_1)
            addPacket(PACKET_RESPONSE_1)
        }

        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaManager::class, JsonObject().apply {
                    put("recording", JsonObject().apply {
                        put("enabled", true)
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_sdp", transaction)
            },
            assert = {
                vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.sdp + "_session") { event ->
                    event.localReply(SDP_SESSION)
                }

                vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
                    val mediaControl = event.body()
                    context.verify {
                        assertEquals(transaction.createdAt, mediaControl.timestamp)
                        assertEquals(transaction.callId, mediaControl.callId)
                        assertEquals(SDP_SESSION, mediaControl.sdpSession)

                        assertNotNull(mediaControl.recording)
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Validate behaviour on disable recording`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaManager::class, JsonObject().apply {
                    put("recording", JsonObject().apply {
                        put("enabled", true)
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.config_change, JsonObject().apply {
                    put("recording", JsonObject().apply {
                        put("enabled", false)
                    })
                })
            },
            assert = {
                vertx.eventBus().localConsumer<JsonObject>(RoutesCE.media + "_recording_reset") { event ->
                    val message = event.body()
                    context.verify {
                        assertTrue(message.isEmpty)
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
}
