/*
 * Copyright 2018-2022 SIP3.IO, Corp.
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

import io.sip3.commons.domain.media.*
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.rtcpAddress
import io.sip3.salto.ce.util.rtpAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RtprStreamTest {

    companion object {

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "callId_uuid@domain.io"
            caller = "123"
            callee = "456"

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
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = 1579544472674
            createdAt = 1579522272674
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
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = 1579555572674
            createdAt = 1579533372674
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
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = 1579511172674
            createdAt = 1579522272674
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
    fun `Validate RtprStream 'add()' method from 2 RTP`() {
        val stream = RtprStream().apply {
            mediaControl = MEDIA_CONTROL
            RtpReportPayload().apply {
                decode(RTPR_1.encode())
            }.let { add(PACKET_1, it) }

            add(PACKET_2, RTPR_2)
        }

        assertEquals(2, stream.reportCount)

        assertEquals(RTPR_1.createdAt, stream.createdAt)
        assertEquals(RTPR_2.createdAt + RTPR_2.duration, stream.terminatedAt)

        assertTrue(stream.codecNames.contains(RTPR_1.codecName))
        assertEquals((RTPR_1.rFactor + RTPR_2.rFactor) / 2.toDouble(), stream.rFactor)
    }

    @Test
    fun `Validate RtprStream R-Factor threshold`() {
        val stream = RtprStream(50F).apply {
            mediaControl = MEDIA_CONTROL
            add(PACKET_1, RTPR_1)
        }

        assertEquals(1, stream.reportCount)
        assertEquals(1, stream.badReportCount)
    }

    @Test
    fun `Validate RtprStream 'add()' method from RTCP`() {
        val stream = RtprStream().apply {
            mediaControl = MEDIA_CONTROL
            add(PACKET_1_RTCP, RTPR_1_RTCP)
        }

        assertEquals(1, stream.reportCount)
        stream.report.apply {
            assertEquals(RTPR_1_RTCP.source, source)

            assertEquals(RTPR_1_RTCP.reportedAt, reportedAt)
            assertEquals(RTPR_1_RTCP.createdAt, createdAt)
            assertEquals(RTPR_1_RTCP.duration, duration)

            assertEquals(RTPR_1_RTCP.codecName, codecName)
            assertEquals(RTPR_1_RTCP.callId, callId)
            assertEquals(RTPR_1_RTCP.ssrc, ssrc)

            assertEquals(RTPR_1_RTCP.expectedPacketCount, expectedPacketCount)
            assertEquals(RTPR_1_RTCP.lostPacketCount, lostPacketCount)
            assertEquals(RTPR_1_RTCP.receivedPacketCount, receivedPacketCount)
            assertEquals(RTPR_1_RTCP.rejectedPacketCount, rejectedPacketCount)
            assertEquals(RTPR_1_RTCP.markerPacketCount, markerPacketCount)
            assertEquals(RTPR_1_RTCP.fractionLost, fractionLost)

            assertEquals(RTPR_1_RTCP.minJitter, minJitter)
            assertEquals(RTPR_1_RTCP.maxJitter, maxJitter)
            assertEquals(RTPR_1_RTCP.avgJitter, avgJitter)
            assertEquals(RTPR_1_RTCP.lastJitter, lastJitter)
            assertEquals(RTPR_1_RTCP.rFactor, rFactor)
        }

        assertEquals(RTPR_1_RTCP.createdAt, stream.createdAt)
        assertEquals(RTPR_1_RTCP.reportedAt, stream.report.reportedAt)
        assertEquals(RTPR_1_RTCP.createdAt + RTPR_1_RTCP.duration, stream.terminatedAt)

        assertTrue(stream.codecNames.contains(RTPR_1_RTCP.codecName))
    }
}