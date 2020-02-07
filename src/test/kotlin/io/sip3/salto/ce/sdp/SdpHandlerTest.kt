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

package io.sip3.salto.ce.sdp

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.util.callId
import io.sip3.salto.ce.util.transactionId
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SdpHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val CODEC = Codec().apply {
            name = "PCMA"
            payloadType = 8
            clockRate = 8000
            ie = 0.0F
            bpl = 4.3F
        }

        val CONFIG = JsonObject().apply {
            put("codecs", JsonArray().apply {
                add(JsonObject.mapFrom(CODEC))
            })
        }

        // Valid SIP Message (INVITE)
        val REQUEST_1: SIPMessage = StringMsgParser().parseSIPMessage("""
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

        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message response (200 Ok) on REQUEST_1 (INVITE)
        val RESPONSE_1: SIPMessage = StringMsgParser().parseSIPMessage("""
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

        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message (INVITE) without response
        val REQUEST_2: SIPMessage = StringMsgParser().parseSIPMessage("""
        INVITE sip:000155917690@ss63.invite.demo.sip3.io:5060 SIP/2.0
        Via: SIP/2.0/UDP 10.177.131.211:6333;branch=z9hG4bKmqffet30b03pp5mv5jj0.1
        From: <sip:000260971282@demo.sip3.io>;tag=82-2zyzysoabqjb3
        To: <sip:000155917690@demo.sip3.io:5060>
        Call-ID: some-another-callId
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
        m=audio 35176 RTP/AVP 0
        c=IN IP4 10.177.131.228
        a=rtpmap:8 PCMA/8000
        a=sendrecv
        a=ptime:20

        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message (INVITE) with Content-Type: multipart/mixed;
        val REQUEST_3: SIPMessage = StringMsgParser().parseSIPMessage("""
        INVITE sip:000155917691@ss63.invite.demo.sip3.io:5060 SIP/2.0
        Via: SIP/2.0/UDP 10.177.131.228:5080;branch=z9hG4bKnrepl90078kel09p9tt0.1
        Max-Forwards: 65
        User-Agent: FPBX-13.0.197(13.23.1)
        Date: Thu, 05 Sep 2019 07:33:00 GMT
        Allow: INVITE,ACK,CANCEL,OPTIONS,BYE,REFER,SUBSCRIBE,NOTIFY,INFO,PUBLISH,MESSAGE,PRACK
        Supported: 100rel
        Content-Type: multipart/mixed; boundary=unique-boundary-1
        Content-Length: 454
        Expires: 180
        From: "000155917690"<sip:000155917690@10.177.131.228:5160>;tag=A47E3246313536415880E070
        To: sip:000155917691@10.177.131.228:5060
        Call-ID: 020F842A6F81400090A336FF@SFESIP1-id1-ext
        CSeq: 1 INVITE
        Contact: <sip:000155917690@10.177.131.228:5080;transport=udp>
        MIME-Version: 1.0
        Content-Disposition: signal;handling=optional

        --unique-boundary-1
        Content-Type: application/sdp

        v=0
        o=root 9858820 9858821 IN IP4 10.177.94.5
        s=Asterisk PBX 13.23.1
        c=IN IP4 10.177.94.5
        t=0 0
        m=audio 59668 RTP/AVP 8 0 3 9
        a=rtpmap:8 PCMA/8000
        a=rtpmap:0 PCMU/8000
        a=rtpmap:3 GSM/8000
        a=rtpmap:9 G722/8000
        a=maxptime:150
        a=sendrecv
        a=ptime:20

        --unique-boundary-1
        Content-Type: application/ISUP;base=itu-t92+;version=itu-t92+



        5p

        --unique-boundary-1--

        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message response (183 Session Progress) with SDP content on REQUEST_3 (INVITE)
        val RESPONSE_3: SIPMessage = StringMsgParser().parseSIPMessage("""
        SIP/2.0 183 Session Progress
        Via: SIP/2.0/UDP 10.177.131.228:5080;branch=z9hG4bKnrepl90078kel09p9tt0.1
        From: "000155917690"<sip:000155917690@10.177.131.228:5160>;tag=A47E3246313536415880E070
        To: <sip:000155917691@10.177.131.228:5060>;tag=D.BVCDC030_Zhhg5
        Call-ID: 020F842A6F81400090A336FF@SFESIP1-id1-ext
        CSeq: 1 INVITE
        Content-Length: 154
        RSeq: 1
        Contact: <sip:10.190.90.82:5060;yop=00.00.122019D9.0000.7024;transport=udp>
        Require: 100rel
        Supported: 100rel,precondition,timer
        Allow: ACK,BYE,CANCEL,INFO,INVITE,NOTIFY,OPTIONS,PRACK,UPDATE
        Content-Type: application/sdp
        Content-Disposition: session;handling=required

        v=0
        o=- 0 0 IN IP4 10.249.177.194
        s=-
        c=IN IP4 10.196.8.220
        t=0 0
        m=audio 29520 RTP/AVP 8
        b=AS:80
        a=rtpmap:8 PCMA/8000
        a=ptime:20
        a=maxptime:20

        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message (INVITE) without codec description (a=)
        val REQUEST_4: SIPMessage = StringMsgParser().parseSIPMessage("""
        INVITE sip:000155917690@10.177.94.5:5080;transport=udp;gw=dd39cef4-5801-4a45-9af8-279a73371518 SIP/2.0
        Max-Forwards: 68
        Via: SIP/2.0/UDP 79.104.212.156:5060;branch=z9hG4bKg3Zqkv7ire9ruovboso4hm4njjprxhucp
        To: "000155917690 000155917690" <sip:000155917690@10.249.177.194>
        From: <sip:000155917691@10.249.177.194;user=phone>;tag=h7g4Esbg_420545701-1568717783909-
        Call-ID: BW135623909170919-10105426@10.64.248.6
        CSeq: 527360435 INVITE
        Contact: <sip:sgc_c@79.104.212.156;transport=udp>
        Record-Route: <sip:79.104.212.156;transport=udp;lr>
        Call-Info: <sip:10.64.248.6>;appearance-index=1
        Min-Se: 180
        P-Asserted-Identity: <sip:+000155917691@10.249.177.194;user=phone>
        Privacy: none
        Session-Expires: 1801;refresher=uac
        Supported: 100rel
        Supported: timer
        Content-Type: application/sdp
        Content-Length: 194
        Recv-Info: x-broadworks-client-session-info
        Allow: ACK, BYE, CANCEL, INFO, INVITE, OPTIONS, PRACK, REFER, NOTIFY, UPDATE
        Accept: application/media_control+xml
        Accept: application/sdp
        Accept: application/x-hotsip-filetransfer+xml
        Accept: multipart/mixed
        
        v=0
        o=BroadWorks 3730020161 1 IN IP4 79.104.212.156
        s=-
        c=IN IP4 79.104.212.169
        t=0 0
        m=audio 20522 RTP/AVP 8 99 18
        a=rtpmap:99 telephone-event/8000
        a=fmtp:99 0-15
        a=fmtp:18 annexb=yes
        """.trimIndent().toByteArray(), true, false, null)

        // Valid SIP Message response (200 Ok) on REQUEST_4 (INVITE)
        val RESPONSE_4: SIPMessage = StringMsgParser().parseSIPMessage("""
        SIP/2.0 200 OK
        Via: SIP/2.0/UDP 79.104.212.156:5060;branch=z9hG4bKg3Zqkv7ire9ruovboso4hm4njjprxhucp
        Record-Route: <sip:79.104.212.156;transport=udp;lr>
        From: <sip:000155917691@10.249.177.194;user=phone>;tag=h7g4Esbg_420545701-1568717783909-
        To: "000155917690 000155917690" <sip:000155917690@10.249.177.194>;tag=20ZvpaH15K34m
        Call-ID: BW135623909170919-10105426@10.64.248.6
        CSeq: 527360435 INVITE
        Contact: <sip:000155917690@10.177.94.5:5080;transport=udp>
        User-Agent: FreeSWITCH
        Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
        Require: timer
        Supported: timer, path, replaces
        Allow-Events: talk, hold, conference, refer
        Session-Expires: 1801;refresher=uac
        Content-Type: application/sdp
        Content-Disposition: session
        Content-Length: 219
        P-Asserted-Identity: "500" <sip:501@10.249.177.194>
        
        v=0
        o=FreeSWITCH 1568700563 1568700564 IN IP4 10.177.94.5
        s=FreeSWITCH
        c=IN IP4 10.177.94.5
        t=0 0
        m=audio 17220 RTP/AVP 8 99
        a=rtpmap:8 PCMA/8000
        a=rtpmap:99 telephone-event/8000
        a=fmtp:99 0-16
        a=ptime:30
        """.trimIndent().toByteArray(), true, false, null)
    }

    @Test
    fun `Handle 2 Request SIP messages and one Response with SDP`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SdpHandler::class, JsonObject())
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_2), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_1), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, RESPONSE_1), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) { event ->
                        context.verify {
                            val sessions = event.body()
                            assertEquals(2, sessions.size)
                            val session1 = sessions[0]
                            val session2 = sessions[1]

                            assertNotEquals(session1.id, session2.id)
                            assertEquals(session1.callId, session2.callId)
                            assertEquals(REQUEST_1.callId(), session1.callId)
                            assertEquals(session1.codec, session2.codec)
                            assertEquals(20, session1.ptime)

                            session1.codec.apply {
                                assertEquals("UNDEFINED", name)
                                assertEquals(8000, clockRate)
                                assertEquals(0x08.toByte(), payloadType)
                                assertEquals(5.0F, ie)
                                assertEquals(10.0F, bpl)
                            }

                            context.completeNow()
                        }
                    }
                }
        )
    }

    @Test
    fun `Handle SDP in multipart content type`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SdpHandler::class, CONFIG)
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_3), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, RESPONSE_3), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) { event ->
                        context.verify {
                            val sessions = event.body()
                            assertEquals(2, sessions.size)
                            val session1 = sessions[0]
                            val session2 = sessions[1]

                            assertNotEquals(session1.id, session2.id)
                            assertEquals(session1.callId, session2.callId)
                            assertEquals(REQUEST_3.callId(), session1.callId)
                            assertEquals(session1.codec, session2.codec)
                            assertEquals(20, session1.ptime)

                            session1.codec.apply {
                                assertEquals(CODEC.name, name)
                                assertEquals(CODEC.clockRate, clockRate)
                                assertEquals(CODEC.payloadType, payloadType)
                                assertEquals(CODEC.ie, ie)
                                assertEquals(CODEC.bpl, bpl)
                            }
                        }

                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Handle only Response SIP message with SDP`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SdpHandler::class, JsonObject())
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_2), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, RESPONSE_1), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) {
                        context.failNow(AssertionError("No events expected in `${RoutesCE.sdp_info}`"))
                    }

                    // Wait for events
                    vertx.setTimer(1500L) {
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Handle SDP session termination`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SdpHandler::class, JsonObject())
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_3), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_TERMINATE, REQUEST_3.transactionId()), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, RESPONSE_3), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) {
                        context.failNow(AssertionError("No events expected in `${RoutesCE.sdp_info}`"))
                    }

                    // Wait for events
                    vertx.setTimer(1500L) {
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Handle SDP content without codec description in request`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(SdpHandler::class, CONFIG)
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, REQUEST_4), USE_LOCAL_CODEC)
                    vertx.eventBus().send(RoutesCE.sdp_session, Pair(SdpHandler.CMD_HANDLE, RESPONSE_4), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) { event ->
                        val sessions = event.body()
                        val session = sessions.first()

                        context.verify {
                            assertEquals(REQUEST_4.callId(), session.callId)
                            assertEquals(30, session.ptime)

                            session.codec.apply {
                                assertEquals(CODEC.name, name)
                                assertEquals(CODEC.clockRate, clockRate)
                                assertEquals(CODEC.payloadType, payloadType)
                                assertEquals(CODEC.ie, ie)
                                assertEquals(CODEC.bpl, bpl)
                            }
                        }

                        context.completeNow()
                    }
                }
        )
    }
}