/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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

import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.rtpr.RtprSession
import io.sip3.salto.ce.util.rtpAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaSessionTest {

    companion object {

        // SDP Request
        val SDP_SESSION_1 = SdpSession().apply {
            callId = "callId_uuid@domain.io"
            timestamp = System.currentTimeMillis()

            address = "10.10.10.10"
            rtpPort = 10500
            rtcpPort = rtpPort + 1

            codecs = mutableListOf(Codec().apply {
                name = "PCMU"
                payloadTypes = listOf(0)
                clockRate = 8000
                bpl = 4.3F
                ie = 0F
            })
        }

        // SDP Response
        val SDP_SESSION_2 = SdpSession().apply {
            callId = "callId_uuid@domain.io"
            timestamp = System.currentTimeMillis()

            address = "10.20.20.20"
            rtpPort = 20500
            rtcpPort = rtpPort + 1

            codecs = mutableListOf(Codec().apply {
                name = "PCMU"
                payloadTypes = listOf(0)
                clockRate = 8000
                bpl = 4.3F
                ie = 0F
            })
        }

        val RTPR_1 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 2

            callId = "callId_uuid@domain.io"

            expectedPacketCount = 3
            receivedPacketCount = 4
            lostPacketCount = 5
            rejectedPacketCount = 6

            duration = 7

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            rFactor = 12F
            mos = 13F
            fractionLost = 14F

            createdAt = 1579511172674
            startedAt = 1579522272674
        }

        val RTPR_1_RTCP = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTCP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 2

            callId = "callId_uuid@domain.io"

            expectedPacketCount = 3
            receivedPacketCount = 4
            lostPacketCount = 5
            rejectedPacketCount = 6

            duration = 7

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            rFactor = 12F
            mos = 13F
            fractionLost = 14F

            createdAt = 1579511172674
            startedAt = 1579522272674
        }

        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 3

            callId = "callId_uuid@domain.io"

            expectedPacketCount = 4
            receivedPacketCount = 5
            lostPacketCount = 6
            rejectedPacketCount = 7

            duration = 8

            lastJitter = 9F
            avgJitter = 10F
            minJitter = 11F
            maxJitter = 12F

            rFactor = 13F
            mos = 14F
            fractionLost = 15F

            createdAt = 1579511272674
            startedAt = 1579522372674
        }

        val RTPR_2_RTCP = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTCP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 3

            callId = "callId_uuid@domain.io"

            expectedPacketCount = 4
            receivedPacketCount = 5
            lostPacketCount = 6
            rejectedPacketCount = 7

            duration = 8

            lastJitter = 9F
            avgJitter = 10F
            minJitter = 11F
            maxJitter = 12F

            rFactor = 13F
            mos = 14F
            fractionLost = 15F

            createdAt = 1579511272674
            startedAt = 1579522372674
        }

        val PACKET_1 = Packet().apply {
            srcAddr = SDP_SESSION_1.rtpAddress()
            dstAddr = SDP_SESSION_2.rtpAddress()
        }

        val PACKET_2 = Packet().apply {
            srcAddr = SDP_SESSION_2.rtpAddress()
            dstAddr = SDP_SESSION_1.rtpAddress()
        }

        val RTPR_SESSION_1 = RtprSession(PACKET_1).apply {
            sdp = Pair(SDP_SESSION_1, SDP_SESSION_2)
            add(RTPR_1)
        }

        val RTPR_SESSION_1_RTCP = RtprSession(PACKET_1).apply {
            sdp = Pair(SDP_SESSION_1, SDP_SESSION_2)
            add(RTPR_1_RTCP)
        }

        val RTPR_SESSION_2 = RtprSession(PACKET_2).apply {
            sdp = Pair(SDP_SESSION_1, SDP_SESSION_2)
            add(RTPR_2)
        }

        val RTPR_SESSION_2_RTCP = RtprSession(PACKET_2).apply {
            sdp = Pair(SDP_SESSION_1, SDP_SESSION_2)
            add(RTPR_2_RTCP)
        }
    }

    @Test
    fun `Create MediaSession from RtprSession`() {
        val session = MediaSession(PACKET_1.srcAddr, PACKET_1.dstAddr, RTPR_1.callId!!)

        // Add Rtpr Session for forward RTP Stream
        session.add(RTPR_SESSION_1)

        // Assert
        assertEquals(RTPR_SESSION_1.createdAt, session.createdAt)
        assertEquals(RTPR_SESSION_1.terminatedAt, session.terminatedAt)

        assertEquals(RTPR_SESSION_1, session.forward.rtp)
        assertNull(session.forward.rtcp)
        assertNull(session.reverse.rtp)
        assertNull(session.reverse.rtcp)

        assertEquals(1, session.codecNames.size)
        assertTrue(session.isOneWay)
        assertFalse(session.hasUndefinedCodec)

        assertEquals(RTPR_SESSION_1.mos, session.mos.toFloat())
        assertEquals(RTPR_SESSION_1.rFactor, session.rFactor.toFloat())

        assertEquals(1, session.reportCount)
        assertEquals(0, session.badReportCount)
        assertEquals(0.0, session.badReportFraction)

        // Add Rtpr Session for reverse RTP Stream
        session.add(RTPR_SESSION_2)

        // Assert
        assertEquals(RTPR_SESSION_1.createdAt, session.createdAt)
        assertEquals(RTPR_SESSION_2.terminatedAt, session.terminatedAt)

        assertEquals(RTPR_SESSION_1, session.forward.rtp)
        assertNull(session.forward.rtcp)
        assertEquals(RTPR_SESSION_2, session.reverse.rtp)
        assertNull(session.reverse.rtcp)

        assertEquals(1, session.codecNames.size)
        assertFalse(session.isOneWay)
        assertFalse(session.hasUndefinedCodec)

        assertEquals(RTPR_SESSION_1.mos, session.mos.toFloat())
        assertEquals(RTPR_SESSION_1.rFactor, session.rFactor.toFloat())

        assertEquals(2, session.reportCount)
        assertEquals(0, session.badReportCount)
        assertEquals(0.0, session.badReportFraction)

        // Add Rtpr Sessions from RTCP
        session.add(RTPR_SESSION_1_RTCP)
        session.add(RTPR_SESSION_2_RTCP)

        // Assert
        assertEquals(RTPR_SESSION_1, session.forward.rtp)
        assertEquals(RTPR_SESSION_2_RTCP, session.forward.rtcp)
        assertEquals(RTPR_SESSION_2, session.reverse.rtp)
        assertEquals(RTPR_SESSION_1_RTCP, session.reverse.rtcp)

        assertEquals(2, session.reportCount)
        assertEquals(0, session.badReportCount)
        assertEquals(0.0, session.badReportFraction)
    }
}