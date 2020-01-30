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

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SIPMessageUtilTest {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val REQUEST: SIPMessage = StringMsgParser().parseSIPMessage("""
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
        a=sendrecv
        a=ptime:20

        """.trimIndent().toByteArray(), true, false, null)

        val RESPONSE_100: SIPMessage = StringMsgParser().parseSIPMessage("""
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
        """.trimIndent().toByteArray(), true, false, null)

        val RESPONSE_183: SIPMessage = StringMsgParser().parseSIPMessage("""
        SIP/2.0 183 Session Progress
        Supported: 100rel,precondition,timer
        Content-Type: application/sdp
        Content-Disposition: session;handling=required
        Allow: ACK,BYE,CANCEL,INFO,INVITE,OPTIONS,PRACK
        Contact: <sip:000155917690@10.177.141.80:5060>;expires=180
        From: <sip:000260971282@demo.sip3.io>;tag=82-2zyzysoabqjb3
        To: <sip:000155917690@demo.sip3.io:5060>;tag=56B5324631353641B4C0D0A8
        Call-ID: 2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e
        CSeq: 42 INVITE
        Via: SIP/2.0/UDP 10.177.131.211:6333;branch=z9hG4bKmqffet30b03pp5mv5jj0.1;received=10.177.131.211
        Content-Length: 153

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

        val RESPONSE_200: SIPMessage = StringMsgParser().parseSIPMessage("""
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
        a=ptime:20
        """.trimIndent().toByteArray(), true, false, null)

        val REQUEST_MULTIPART: SIPMessage = StringMsgParser().parseSIPMessage("""
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
    }

    @Test
    fun `Check callId() method extension`() {
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e", REQUEST.callId())
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e", RESPONSE_183.callId())
    }

    @Test
    fun `Check branchId() method extension`() {
        assertEquals("z9hG4bKmqffet30b03pp5mv5jj0.1", REQUEST.branchId())
        assertEquals("z9hG4bKmqffet30b03pp5mv5jj0.1", RESPONSE_183.branchId())
    }

    @Test
    fun `Check toUri() method extension`() {
        assertEquals("sip:000155917690@demo.sip3.io:5060", REQUEST.toUri())
        assertEquals("sip:000155917690@demo.sip3.io:5060", RESPONSE_183.toUri())
    }

    @Test
    fun `Check toUserOrNumber() method extension`() {
        assertEquals("000155917690", REQUEST.toUserOrNumber())
        assertEquals("000155917690", RESPONSE_183.toUserOrNumber())
    }

    @Test
    fun `Check fromUri() method extension`() {
        assertEquals("sip:000260971282@demo.sip3.io", REQUEST.fromUri())
        assertEquals("sip:000260971282@demo.sip3.io", RESPONSE_183.fromUri())
    }

    @Test
    fun `Check fromUserOrNumber() method extension`() {
        assertEquals("000260971282", REQUEST.fromUserOrNumber())
        assertEquals("000260971282", RESPONSE_183.fromUserOrNumber())
    }

    @Test
    fun `Check cseqMethod() method extension`() {
        assertEquals("INVITE", REQUEST.cseqMethod())
        assertEquals("INVITE", RESPONSE_183.cseqMethod())
    }

    @Test
    fun `Check cseqNumber() method extension`() {
        assertEquals(1, REQUEST.cseqNumber())
        assertEquals(42, RESPONSE_183.cseqNumber())
    }

    @Test
    fun `Check method() method extension`() {
        assertEquals("INVITE", REQUEST.method())
        assertNull(RESPONSE_183.method())
    }

    @Test
    fun `Check statusCode() method extension`() {
        assertNull(REQUEST.statusCode())
        assertEquals(183, RESPONSE_183.statusCode())
    }

    @Test
    fun `Check transactionId() method extension`() {
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e:z9hG4bKmqffet30b03pp5mv5jj0.1:1", REQUEST.transactionId())
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e:z9hG4bKmqffet30b03pp5mv5jj0.1:42", RESPONSE_183.transactionId())
    }

    @Test
    fun `Check hasSdp() method`() {
        assertTrue(REQUEST.hasSdp())
        assertTrue(RESPONSE_183.hasSdp())
        assertTrue(RESPONSE_200.hasSdp())

        assertFalse(RESPONSE_100.hasSdp())
    }

    @Test
    fun `Check sessionDescription() method`() {
        assertNotNull(REQUEST.sessionDescription())
        assertNotNull(RESPONSE_183.sessionDescription())
        assertNotNull(RESPONSE_200.sessionDescription())
        assertNotNull(REQUEST_MULTIPART.sessionDescription())

        assertNull(RESPONSE_100.sessionDescription())
    }

    @Test
    fun `Check headersMap() method extension`() {
        var headersMap = REQUEST.headersMap()
        assertEquals(17, headersMap.size)
        assertEquals("INVITE sip:000155917690@ss63.invite.demo.sip3.io:5060 SIP/2.0", headersMap["request-line"])
        assertEquals("1 INVITE", headersMap["cseq"])
        headersMap = RESPONSE_183.headersMap()
        assertEquals(12, headersMap.size)
        assertEquals("SIP/2.0 183 Session Progress", headersMap["status-line"])
        assertEquals("100rel,precondition,timer", headersMap["supported"])
    }
}