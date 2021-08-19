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

package io.sip3.salto.ce.util


import gov.nist.javax.sip.parser.StringMsgParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.restcomm.media.sdp.fields.MediaDescriptionField
import org.restcomm.media.sdp.ice.attributes.CandidateAttribute


class MediaDescriptionFieldUtilTest {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val REQUEST_DESCRIPTION: MediaDescriptionField = StringMsgParser().parseSIPMessage(
            """
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
        X-Diversion: First
        X-Diversion: Second
        Min-SE: 900
        Max-Forwards: 63
        User-Agent: ITLCS 3.8.1
        Content-Type: application/sdp
        Content-Length: 179

        v=0
        o=- 677480114 3140674329 IN IP4 10.177.131.228
        s=centrex-mediagateway
        t=0 0
        m=audio 35176 RTP/AVP 8
        c=IN IP4 10.177.131.228
        a=rtpmap:8 PCMA/8000
        a=rtcp-mux
        a=rtcp:35176 IN IP4 10.177.131.228
        a=sendrecv
        a=ptime:20

        """.trimIndent().toByteArray(), true, false, null
        )
            .sessionDescription()!!.getMediaDescription("audio")

        val REQUEST_DESCRIPTION_ICE: MediaDescriptionField = StringMsgParser().parseSIPMessage(
            """
        INVITE sip:0825959500@cfdp.tb-cloud.fr SIP/2.0
        Via: SIP/2.0/WSS 192.0.2.68;branch=z9hG4bK4229189
        To: <sip:0825959500@cfdp.tb-cloud.fr>
        From: "Laurence BELHOCINE-GIRARD" <sip:rqj44cv2@cfdp.tb-cloud.fr>;tag=793246322l
        CSeq: 1 INVITE
        Call-ID: qjp3fr95vfi3i3601oa9
        Max-Forwards: 70
        Contact: <sip:neo8j26g@192.0.2.68;transport=wss;ob>
        Allow: ACK,CANCEL,INVITE,MESSAGE,BYE,OPTIONS,INFO,NOTIFY,REFER
        Supported: outbound
        User-Agent: wazo-wda: 1.11.8-rc.29
        Content-Type: application/sdp
        Content-Length: 2387
        
        v=0
        o=- 4386732902248422319 3 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        a=msid-semantic: WMS 225e6e1d-3a66-444a-bd10-5942e14dafa9
        m=audio 41999 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
        c=IN IP4 88.167.120.240
        a=rtcp:9 IN IP4 0.0.0.0
        a=candidate:2342127660 1 udp 2122260223 172.18.32.1 61818 typ host generation 0 network-id 1
        a=candidate:1621512748 1 udp 2122194687 10.242.2.7 61819 typ host generation 0 network-id 2
        a=candidate:1897852119 1 udp 2122129151 192.168.1.28 61820 typ host generation 0 network-id 3
        a=candidate:3306812636 1 tcp 1518280447 172.18.32.1 9 typ host tcptype active generation 0 network-id 1
        a=candidate:774221532 1 tcp 1518214911 10.242.2.7 9 typ host tcptype active generation 0 network-id 2
        a=candidate:1067257895 1 tcp 1518149375 192.168.1.28 9 typ host tcptype active generation 0 network-id 3
        a=candidate:2265879811 1 udp 1685921535 88.167.120.240 41999 typ srflx raddr 192.168.1.28 rport 61820 generation 0 network-id 3
        a=ice-ufrag:1eV6
        a=ice-pwd:nYI6xByGt6nASclhRovxqqNY
        a=ice-options:trickle
        a=fingerprint:sha-256 88:28:5C:EC:00:F3:77:1F:B5:A8:02:BD:A7:60:E6:D9:31:35:1B:08:27:A5:23:EA:F4:9E:8D:7E:19:EB:62:7B
        a=setup:actpass
        a=mid:0
        a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
        a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
        a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
        a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
        a=extmap:5 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id
        a=extmap:6 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id
        a=sendrecv
        a=msid:225e6e1d-3a66-444a-bd10-5942e14dafa9 fbdf5ae0-5f3d-4ef3-8764-d98bcbea23fd
        a=rtcp-mux
        a=rtpmap:111 opus/48000/2
        a=rtcp-fb:111 transport-cc
        a=fmtp:111 minptime=10;useinbandfec=1
        a=rtpmap:103 ISAC/16000
        a=rtpmap:104 ISAC/32000
        a=rtpmap:9 G722/8000
        a=rtpmap:0 PCMU/8000
        a=rtpmap:8 PCMA/8000
        a=rtpmap:106 CN/32000
        a=rtpmap:105 CN/16000
        a=rtpmap:13 CN/8000
        a=rtpmap:110 telephone-event/48000
        a=rtpmap:112 telephone-event/32000
        a=rtpmap:113 telephone-event/16000
        a=rtpmap:126 telephone-event/8000
        a=ssrc:209929341 cname:zzsMjmAY7hT3oDEI
        a=ssrc:209929341 msid:225e6e1d-3a66-444a-bd10-5942e14dafa9 fbdf5ae0-5f3d-4ef3-8764-d98bcbea23fd
        a=ssrc:209929341 mslabel:225e6e1d-3a66-444a-bd10-5942e14dafa9
        a=ssrc:209929341 label:fbdf5ae0-5f3d-4ef3-8764-d98bcbea23fd
        
        """.trimIndent().toByteArray(), true, false, null
        )
            .sessionDescription()!!.getMediaDescription("audio")

        val RESPONSE_DESCRIPTION: MediaDescriptionField = StringMsgParser().parseSIPMessage(
            """
        SIP/2.0 200 OK
        Via: SIP/2.0/UDP 79.104.212.156:5060;branch=z9hG4bKg3Zqkv7ire9ruovboso4hm4njjprxhucp
        Record-Route: <sip:79.104.212.156;transport=udp;lr>
        From: <sip:000155917691@10.249.177.194;user=phone>;tag=h7g4Esbg_420545701-1568717783909-
        To: "000155917690 000155917690" <sip:000155917690@10.249.177.194>;tag=20ZvpaH15K34m
        Call-ID: BW135623909170919-10105426@10.64.248.6
        CSeq: 527360435 INVITE
        Contact: <sip:000155917690@10.177.94.5:5080;transport=udp>;expires=180
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
        """.trimIndent().toByteArray(), true, false, null
        )
            .sessionDescription()!!.getMediaDescription("audio")

    }

    @Test
    fun `Check defineRtcpPort() method`() {
        assertEquals(REQUEST_DESCRIPTION.port, REQUEST_DESCRIPTION.defineRtcpPort(true))
        assertEquals(REQUEST_DESCRIPTION.port + 1, REQUEST_DESCRIPTION.defineRtcpPort(false))

    }

    @Test
    fun `Check ptime() method`() {
        assertEquals(20, REQUEST_DESCRIPTION.ptime())
        assertNull(RESPONSE_DESCRIPTION.ptime())
    }

    @Test
    fun `Check address() method`() {
        assertEquals("10.177.131.228", REQUEST_DESCRIPTION.address())
        assertEquals("10.177.94.5", RESPONSE_DESCRIPTION.address())
    }

    @Test
    fun `Check ICE candidates`() {

        REQUEST_DESCRIPTION_ICE.candidates.forEach { candidateAttribute ->
            println(candidateAttribute.candidateType == CandidateAttribute.TYP_SRFLX)
        }

        println("end")
    }
}
