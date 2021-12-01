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

package io.sip3.salto.ce.rtpr

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil
import kotlin.math.max
import kotlin.math.min

class RtprStream(packet: Packet, private val rFactorThreshold: Float? = null) {

    var createdAt: Long = 0L
    var terminatedAt: Long = 0L

    var srcAddr = packet.srcAddr
    var dstAddr = packet.dstAddr

    var report: RtpReportPayload = RtpReportPayload().apply {
        createdAt = System.currentTimeMillis()
    }

    val source: Byte
        get() = report.source

    var mediaControl: MediaControl? = null
    val codecNames = mutableSetOf<String>()
    val callId: String?
        get() = mediaControl?.callId

    val mos: Double?
        get() = report.mos.takeIf { it != 1F }?.toDouble()

    val rFactor: Double?
        get() = report.rFactor.takeIf { it != 0F }?.toDouble()

    var reportCount = 0
    var badReportCount = 0

    fun add(payload: RtpReportPayload) {
        codecNames.add(payload.codecName ?: "UNDEFINED(${payload.payloadType})")

        report.mergeIn(payload)

        createdAt = report.createdAt
        terminatedAt = report.createdAt + report.duration

        reportCount++
        rFactorThreshold?.let { if (report.rFactor in 0F..rFactorThreshold) badReportCount++ }
    }

    private fun RtpReportPayload.mergeIn(other: RtpReportPayload) {
        if (source < 0) source = other.source
        if (ssrc == 0L) ssrc = other.ssrc
        if (callId == null) callId = other.callId
        if (codecName == null) codecName = other.codecName
        if (payloadType < 0) payloadType = other.payloadType

        expectedPacketCount += other.expectedPacketCount
        receivedPacketCount += other.receivedPacketCount
        rejectedPacketCount += other.rejectedPacketCount
        lostPacketCount += other.lostPacketCount
        markerPacketCount += other.markerPacketCount

        duration += other.duration

        if (reportedAt < other.reportedAt) lastJitter = other.lastJitter
        avgJitter = (avgJitter * reportCount + other.avgJitter) / (reportCount + 1)
        minJitter = min(minJitter, other.minJitter)
        maxJitter = max(maxJitter, other.maxJitter)

        if (rFactor > 0.0F || other.rFactor > 0.0F) {
            rFactor = (rFactor * reportCount + other.rFactor) / (reportCount + 1)
            mos = MediaUtil.computeMos(rFactor)
        }
        fractionLost = lostPacketCount.toFloat() / expectedPacketCount

        createdAt = min(createdAt, other.createdAt)
        reportedAt = max(reportedAt, other.reportedAt)
    }
}