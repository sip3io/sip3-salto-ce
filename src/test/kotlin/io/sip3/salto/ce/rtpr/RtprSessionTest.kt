/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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
import io.sip3.salto.ce.util.MediaUtil
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
            fractionLost = 14F

            reportedAt = 1579511172674
            createdAt = 1579522272674
        }

        val PACKET_1 = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.src.rtpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.dst.rtpAddress()
            attributes = mutableMapOf("custom_attr" to "value")
        }

        val PACKET_1_RTCP = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.dst.rtcpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.src.rtcpAddress()
        }

        val PACKET_2 = Packet().apply {
            srcAddr = MEDIA_CONTROL.sdpSession.dst.rtpAddress()
            dstAddr = MEDIA_CONTROL.sdpSession.src.rtpAddress()
        }
    }

    @Test
    fun `Validate RtprSession 'create()' static method for RTP`() {
        val session = RtprSession.create(
            RTPR_1.source,
            MEDIA_CONTROL,
            PACKET_1
        )

        assertEquals(MEDIA_CONTROL.callId, session.callId)
        assertEquals(MEDIA_CONTROL.caller, session.caller)
        assertEquals(MEDIA_CONTROL.callee, session.callee)

        assertEquals(MEDIA_CONTROL.sdpSession.src.rtpAddress(), session.srcAddr)
        assertEquals(MEDIA_CONTROL.sdpSession.dst.rtpAddress(), session.dstAddr)

        assertEquals(0.0, session.badReportFraction)
    }

    @Test
    fun `Validate RtprSession 'create()' static method for RTCP`() {
        val session = RtprSession.create(
            RTPR_1_RTCP.source,
            MEDIA_CONTROL,
            PACKET_1_RTCP
        )

        assertEquals(MEDIA_CONTROL.callId, session.callId)
        assertEquals(MEDIA_CONTROL.caller, session.caller)
        assertEquals(MEDIA_CONTROL.callee, session.callee)

        assertEquals(MEDIA_CONTROL.sdpSession.src.rtcpAddress(), session.srcAddr)
        assertEquals(MEDIA_CONTROL.sdpSession.dst.rtcpAddress(), session.dstAddr)

        assertEquals(0.0, session.badReportFraction)
    }

    @Test
    fun `Validate RtprSession 'add()' method from RTP`() {
        val session = RtprSession.create(
            RTPR_1.source,
            MEDIA_CONTROL,
            PACKET_1
        )
        session.add(PACKET_1, RTPR_1)

        assertEquals(1, session.reportCount)
        assertReport(RtprStreamTest.RTPR_1, session.forward!!.report)

        assertEquals(RTPR_1.createdAt, session.createdAt)
        assertEquals(RTPR_1.createdAt + RTPR_1.duration, session.terminatedAt)

        assertTrue(session.codecs.contains(RTPR_1.codecName))

        session.add(PACKET_2, RTPR_2)
        assertEquals(2, session.reportCount)
        assertReport(RTPR_2, session.reverse!!.report)

        assertEquals(RTPR_1.createdAt, session.createdAt)
        assertEquals(RTPR_2.createdAt + RTPR_2.duration, session.terminatedAt)

        assertEquals(PACKET_1.attributes!!.get("custom_attr"), session.attributes.get("custom_attr"))

        assertTrue(session.codecs.contains(RTPR_2.codecName))
        assertEquals(MediaUtil.computeMos(RTPR_2.rFactor), session.reverse!!.mos!!.toFloat())
        assertEquals(RTPR_2.rFactor, session.reverse!!.rFactor!!.toFloat())
    }

    @Test
    fun `Validate RtprSession 'add()' method from RTCP`() {
        val session = RtprSession.create(
            RTPR_1_RTCP.source,
            MEDIA_CONTROL,
            PACKET_1_RTCP
        )
        session.add(PACKET_1_RTCP, RTPR_1_RTCP)

        assertEquals(1, session.reportCount)
        assertReport(RtprStreamTest.RTPR_1_RTCP, session.forward!!.report)

        assertEquals(RTPR_1_RTCP.createdAt, session.createdAt)
        assertEquals(RTPR_1_RTCP.createdAt + RTPR_1_RTCP.duration, session.terminatedAt)

        assertTrue(session.codecs.contains(RTPR_1_RTCP.codecName))
        assertEquals(RTPR_1_RTCP.rFactor, session.forward!!.rFactor!!.toFloat())
    }

    private fun assertReport(origin: RtpReportPayload, report: RtpReportPayload) {
        report.apply {
            assertEquals(origin.source, source)

            assertEquals(origin.reportedAt, reportedAt)
            assertEquals(origin.createdAt, createdAt)
            assertEquals(origin.duration, duration)

            assertEquals(origin.codecName, codecName)
            assertEquals(origin.callId, callId)
            assertEquals(origin.ssrc, ssrc)

            assertEquals(origin.expectedPacketCount, expectedPacketCount)
            assertEquals(origin.lostPacketCount, lostPacketCount)
            assertEquals(origin.receivedPacketCount, receivedPacketCount)
            assertEquals(origin.rejectedPacketCount, rejectedPacketCount)
            assertEquals(origin.markerPacketCount, markerPacketCount)
            assertEquals(origin.fractionLost, fractionLost)

            assertEquals(origin.minJitter, minJitter)
            assertEquals(origin.maxJitter, maxJitter)
            assertEquals(origin.avgJitter, avgJitter)
            assertEquals(origin.lastJitter, lastJitter)
            assertEquals(origin.rFactor, rFactor)
            assertEquals(MediaUtil.computeMos(origin.rFactor), mos)
        }
    }
}