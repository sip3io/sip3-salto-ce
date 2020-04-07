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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.parser.StringMsgParser
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class SipTransactionTest {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            attributes["invite"] = true
            attributes[Attributes.caller] = "caller"
            payload = """
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
                        User-Agent: Android Application
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

                    """.trimIndent().toByteArray()
        }

        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            attributes["ringing"] = true
            payload = """
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

                    """.trimIndent().toByteArray()
        }

        val PACKET_3 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            attributes["ringing"] = true
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
    }

    @Test
    fun `Add multiple packets and retrieve all attributes`() {
        val message1 = StringMsgParser().parseSIPMessage(PACKET_1.payload, true, false, null)
        val message2 = StringMsgParser().parseSIPMessage(PACKET_2.payload, true, false, null)

        val transaction = SipTransaction()

        // Add PACKET_1
        transaction.addMessage(PACKET_1, message1)
        assertEquals(PACKET_1.srcAddr.addr, transaction.srcAddr.addr)
        assertEquals(PACKET_1.srcAddr.port, transaction.srcAddr.port)
        assertEquals(PACKET_1.dstAddr.addr, transaction.dstAddr.addr)
        assertEquals(PACKET_1.dstAddr.port, transaction.dstAddr.port)
        assertEquals(message1, transaction.request)
        assertEquals("000260971282", transaction.caller)
        assertEquals("000155917690", transaction.callee)
        assertEquals("caller", transaction.attributes[Attributes.caller])
        assertTrue(transaction.attributes["invite"] as Boolean)
        assertNull(transaction.attributes["retransmits"])

        // Retransmit PACKET_1
        transaction.addMessage(PACKET_1, message1)
        assertTrue(transaction.attributes["retransmits"] as Boolean)

        //  Add PACKET_2
        transaction.addMessage(PACKET_2, message2)
        assertEquals(PACKET_2.dstAddr.addr, transaction.srcAddr.addr)
        assertEquals(PACKET_2.dstAddr.port, transaction.srcAddr.port)
        assertEquals(PACKET_2.srcAddr.addr, transaction.dstAddr.addr)
        assertEquals(PACKET_2.srcAddr.port, transaction.dstAddr.port)
        assertNotNull(transaction.response)
        assertNull(transaction.terminatedAt)
        assertEquals("000260971282", transaction.caller)
        assertEquals("000155917690", transaction.callee)
        assertTrue(transaction.attributes["invite"] as Boolean)
        assertTrue(transaction.attributes["ringing"] as Boolean)
        assertTrue(transaction.attributes["retransmits"] as Boolean)
    }

    @Test
    fun `SIP Transaction with only TRYING message`() {
        val message = StringMsgParser().parseSIPMessage(PACKET_3.payload, true, false, null)
        val transaction = SipTransaction()
        transaction.addMessage(PACKET_3, message)

        assertEquals("INVITE", transaction.cseqMethod)
        assertEquals(PACKET_3.dstAddr.addr, transaction.srcAddr.addr)
        assertEquals(PACKET_3.dstAddr.port, transaction.srcAddr.port)
        assertEquals(PACKET_3.srcAddr.addr, transaction.dstAddr.addr)
        assertEquals(PACKET_3.srcAddr.port, transaction.dstAddr.port)
        assertNotNull(transaction.response)
        assertEquals("123", transaction.caller)
        assertEquals("321", transaction.callee)
        assertEquals("58e44b0c223f11ea8e00c6697351ff4a@176.9.119.117", transaction.callId)
    }
}