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

package io.sip3.salto.ce.util


import gov.nist.javax.sip.parser.StringMsgParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.restcomm.media.sdp.fields.MediaDescriptionField


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
}
