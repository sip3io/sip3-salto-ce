/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

        val RESPONSE: SIPMessage = StringMsgParser().parseSIPMessage("""
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
    }

    @Test
    fun `Check callId() method extension`() {
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e", REQUEST.callId())
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e", RESPONSE.callId())
    }

    @Test
    fun `Check branchId() method extension`() {
        assertEquals("z9hG4bKmqffet30b03pp5mv5jj0.1", REQUEST.branchId())
        assertEquals("z9hG4bKmqffet30b03pp5mv5jj0.1", RESPONSE.branchId())
    }

    @Test
    fun `Check toUri() method extension`() {
        assertEquals("sip:000155917690@demo.sip3.io:5060", REQUEST.toUri())
        assertEquals("sip:000155917690@demo.sip3.io:5060", RESPONSE.toUri())
    }

    @Test
    fun `Check toUserOrNumber() method extension`() {
        assertEquals("000155917690", REQUEST.toUserOrNumber())
        assertEquals("000155917690", RESPONSE.toUserOrNumber())
    }

    @Test
    fun `Check fromUri() method extension`() {
        assertEquals("sip:000260971282@demo.sip3.io", REQUEST.fromUri())
        assertEquals("sip:000260971282@demo.sip3.io", RESPONSE.fromUri())
    }

    @Test
    fun `Check fromUserOrNumber() method extension`() {
        assertEquals("000260971282", REQUEST.fromUserOrNumber())
        assertEquals("000260971282", RESPONSE.fromUserOrNumber())
    }

    @Test
    fun `Check cseqMethod() method extension`() {
        assertEquals("INVITE", REQUEST.cseqMethod())
        assertEquals("INVITE", RESPONSE.cseqMethod())
    }

    @Test
    fun `Check cseqNumber() method extension`() {
        assertEquals(1, REQUEST.cseqNumber())
        assertEquals(42, RESPONSE.cseqNumber())
    }

    @Test
    fun `Check method() method extension`() {
        assertEquals("INVITE", REQUEST.method())
        assertNull(RESPONSE.method())
    }

    @Test
    fun `Check statusCode() method extension`() {
        assertNull(REQUEST.statusCode())
        assertEquals(183, RESPONSE.statusCode())
    }

    @Test
    fun `Check transactionId() method extension`() {
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e:z9hG4bKmqffet30b03pp5mv5jj0.1:1", REQUEST.transactionId())
        assertEquals("2dnuu30ktosoky1uad3nzzk3nkk3nzz3-wdsrwt7@UAC-e-e:z9hG4bKmqffet30b03pp5mv5jj0.1:42", RESPONSE.transactionId())
    }

    @Test
    fun `Check headersMap() method extension`() {
        var headersMap = REQUEST.headersMap()
        assertEquals(17, headersMap.size)
        assertEquals("INVITE sip:000155917690@ss63.invite.demo.sip3.io:5060 SIP/2.0", headersMap["request-line"])
        assertEquals("1 INVITE", headersMap["cseq"])
        headersMap = RESPONSE.headersMap()
        assertEquals(12, headersMap.size)
        assertEquals("SIP/2.0 183 Session Progress", headersMap["status-line"])
        assertEquals("100rel,precondition,timer", headersMap["supported"])
    }
}