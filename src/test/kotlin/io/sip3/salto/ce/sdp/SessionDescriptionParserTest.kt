/*
 * Copyright 2018-2026 SIP3.IO, Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import io.sip3.salto.ce.util.address
import io.sip3.salto.ce.util.ptime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class SessionDescriptionParserTest {

    @Test
    fun `Parse default RTP and RTCP fields`() {
        val parser = SessionDescriptionParser()
        val description = parser.parse(
            """
            v=0
            o=- 677480114 3140674329 IN IP4 10.177.131.228
            s=centrex-mediagateway
            t=0 0
            m=audio 35176 RTP/AVP 8
            c=IN IP4 10.177.131.228
            a=rtpmap:8 PCMA/8000
            a=rtcp-mux
            a=rtcp:35178 IN IP4 10.177.131.228
            a=ptime:20
            a=candidate:2265879811 1 udp 1685921535 88.167.120.240 41999 typ srflx
            """.trimIndent()
        )

        val audio = description!!.getMediaDescription("audio")
        assertNotNull(audio)
        assertEquals(35176, audio.port)
        assertEquals("10.177.131.228", audio.address())
        assertEquals(35178, audio.rtcp.port)
        assertEquals(20, audio.ptime())
        assertNotNull(audio.rtcpMux)
        assertNull(audio.getFormat(8))
        assertNull(audio.getCandidates())
    }

    @Test
    fun `Parse only requested SDP fields`() {
        val parser = SessionDescriptionParser(listOf("v", "o", "s", "t", "m=audio", "c", "a=rtpmap", "a=ptime", "a=candidate"))
        val description = parser.parse(
            """
            v=0
            o=- 677480114 3140674329 IN IP4 10.177.131.228
            s=centrex-mediagateway
            t=0 0
            m=audio 35176 RTP/AVP 8
            c=IN IP4 10.177.131.228
            a=rtpmap:8 PCMA/8000
            a=rtcp-mux
            a=rtcp:35176 IN IP4 10.177.131.228
            a=ptime:20
            a=candidate:2265879811 1 udp 1685921535 88.167.120.240 41999 typ host
            """.trimIndent()
        )

        val audio = description!!.getMediaDescription("audio")
        assertNotNull(audio)
        assertEquals(35176, audio.port)
        assertEquals("10.177.131.228", audio.address())
        assertEquals("PCMA", audio.getFormat(8).codec)
        assertEquals(20, audio.ptime())
        assertNotNull(audio.rtcp)
        assertNotNull(audio.rtcpMux)
        assertEquals(1, audio.candidates.size)
    }

    @Test
    @Disabled
    fun `Skip invalid SDP lines`() {
        val parser = SessionDescriptionParser(listOf("v", "o", "s", "t", "m=audio", "c", "a=rtpmap", "a=ptime"))
        val description = parser.parse(
            """
            v=0
            o=- 677480114 3140674329 IN IP4 10.177.131.228
            s=centrex-mediagateway
            t=0 0
            m=audio 35176 RTP/AVP 8
            c=IN IP4 10.177.131.228
            a=rtpmap:8 PCMA/8000
            a=ptime:invalid
            """.trimIndent()
        )

        val audio = description!!.getMediaDescription("audio")
        assertNotNull(audio)
        assertEquals(35176, audio.port)
        assertEquals("10.177.131.228", audio.address())
        assertEquals("PCMA", audio.getFormat(8).codec)
        assertNull(audio.ptime())
    }

    @Test
    fun `Return null when SDP parsing fails`() {
        val description = SessionDescriptionParser().parse(null)

        assertNull(description)
    }
}
