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

package io.sip3.salto.ce.rtpr

import io.sip3.commons.domain.media.Codec
import io.sip3.commons.domain.media.MediaAddress
import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.Recording
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.rtcpAddress
import io.sip3.salto.ce.util.rtpAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RtprSessionTest {

    companion object {

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "callId_uuid@domain.io"

            sdpSession = SdpSession().apply {
                src = MediaAddress().apply {
                    addr = "10.10.10.10"
                    rtpPort = 10500
                    rtcpPort = rtpPort + 1
                }
                dst = MediaAddress().apply {
                    addr = "10.20.20.20"
                    rtpPort = 20500
                    rtcpPort = rtpPort + 1
                }

                codecs = mutableListOf(Codec().apply {
                    name = "PCMU"
                    payloadTypes = listOf(0)
                    clockRate = 8000
                    bpl = 4.3F
                    ie = 0F
                })
            }

            recording = Recording()
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

            duration = 11100000

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            rFactor = 12F
            mos = 13F
            fractionLost = 14F

            createdAt = 1579544472674
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

            duration = 11100000

            lastJitter = 9F
            avgJitter = 10F
            minJitter = 11F
            maxJitter = 12F

            rFactor = 13F
            mos = 14F
            fractionLost = 15F

            createdAt = 1579555572674
            startedAt = 1579533372674
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

        val PACKET_1 = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.src.rtpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.dst.rtpAddress()
        }

        val PACKET_1_RTCP = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.src.rtcpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.dst.rtcpAddress()
        }

        val PACKET_2 = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.dst.rtpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.src.rtpAddress()
        }
    }


    @Test
    fun `Validate RtprSession 'add()' method from RTP`() {
        val session = RtprSession(PACKET_1).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_1)
        }

        assertEquals(1, session.reportCount)
        assertEquals(RTPR_1, session.report)

        assertEquals(RTPR_1.startedAt, session.createdAt)
        assertEquals(RTPR_1.startedAt + RTPR_1.duration, session.terminatedAt)

        assertTrue(session.codecNames.contains(RTPR_1.codecName))
        assertEquals(RTPR_1.mos, session.mos)
        assertEquals(RTPR_1.rFactor, session.rFactor)
    }

    @Test
    fun `Validate RtprSession 'add()' method from 2 RTP`() {
        val session = RtprSession(PACKET_1).apply {
            mediaControl = MEDIA_CONTROL
            RtpReportPayload().apply {
                decode(RTPR_1.encode())
            }.let { add(it) }

            add(RTPR_2)
        }

        assertEquals(2, session.reportCount)


        assertEquals(RTPR_1.startedAt, session.createdAt)
        assertEquals(RTPR_2.startedAt + RTPR_2.duration, session.terminatedAt)

        assertTrue(session.codecNames.contains(RTPR_1.codecName))
        assertEquals((RTPR_1.rFactor + RTPR_2.rFactor) / 2, session.rFactor)
    }

    @Test
    fun `Validate RtprSession 'merge()' method from RTP`() {
        val session = RtprSession(PACKET_1).apply {
            mediaControl = MEDIA_CONTROL
            RtpReportPayload().apply {
                decode(RTPR_1.encode())
            }.let { add(it) }
        }

        val session2 = RtprSession(PACKET_1).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_2)
        }

        session.merge(session2)

        assertEquals(2, session.reportCount)


        assertEquals(RTPR_1.startedAt, session.createdAt)
        assertEquals(RTPR_2.startedAt + RTPR_2.duration, session.terminatedAt)

        assertTrue(session.codecNames.contains(RTPR_1.codecName))
        assertEquals((RTPR_1.rFactor + RTPR_2.rFactor) / 2, session.rFactor)
    }

    @Test
    fun `Validate RtprSession R-Factor threshold`() {
        val session = RtprSession(PACKET_1, 50F).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_1)
        }

        assertEquals(1, session.reportCount)
        assertEquals(1, session.badReportCount)
    }

    @Test
    fun `Validate RtprSession 'add()' method from RTCP`() {
        val session = RtprSession(PACKET_1_RTCP).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_1_RTCP)
        }

        assertEquals(1, session.reportCount)
        assertEquals(RTPR_1_RTCP, session.report)

        assertEquals(RTPR_1_RTCP.startedAt, session.createdAt)
        assertEquals(RTPR_1_RTCP.startedAt + RTPR_1_RTCP.duration, session.terminatedAt)

        assertTrue(session.codecNames.contains(RTPR_1_RTCP.codecName))
        assertEquals(RTPR_1_RTCP.mos, session.mos)
        assertEquals(RTPR_1_RTCP.rFactor, session.rFactor)
    }
}