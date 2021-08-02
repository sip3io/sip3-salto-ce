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

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil

class RtprStream(packet: Packet, private val rFactorThreshold: Float? = null) {

    var createdAt: Long = 0L
    var terminatedAt: Long = 0L

    var srcAddr = packet.srcAddr
    var dstAddr = packet.dstAddr
    lateinit var report: RtpReportPayload

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

    var lastMos: Double? = null
    var lastRFactor: Double? = null

    var reportCount = 0
    var badReportCount = 0

    fun add(payload: RtpReportPayload) {
        if (reportCount == 0) {
            report = payload
            createdAt = payload.startedAt
            terminatedAt = payload.startedAt + payload.duration
            report.codecName?.let { codecNames.add(it) }
        } else {
            mergeReport(payload)
        }

        reportCount++
        rFactorThreshold?.let { if (report.rFactor in 0F..rFactorThreshold) badReportCount++ }

        lastMos = payload.mos.toDouble()
        lastRFactor = payload.rFactor.toDouble()
    }

    fun merge(other: RtprStream) {
        mergeReport(other.report, other.reportCount)

        reportCount += other.reportCount
        badReportCount += other.badReportCount
    }

    private fun mergeReport(payload: RtpReportPayload, reportCountIncrement: Int = 1) {
        report.apply {
            if (codecName == null) {
                payload.codecName?.let { codecName = it }
            }

            codecNames.add(payload.codecName ?: "UNDEFINED($payloadType)")

            if (callId == null) {
                payload.callId?.let { callId = it }
            }

            expectedPacketCount += payload.expectedPacketCount
            receivedPacketCount += payload.receivedPacketCount
            rejectedPacketCount += payload.rejectedPacketCount
            lostPacketCount += payload.lostPacketCount

            duration += payload.duration
            fractionLost = lostPacketCount.toFloat() / expectedPacketCount

            lastJitter = payload.lastJitter
            avgJitter = (avgJitter * reportCount + payload.avgJitter * reportCountIncrement) /
                    (reportCount + reportCountIncrement)

            if (maxJitter < lastJitter) {
                maxJitter = lastJitter
            }
            if (minJitter > lastJitter) {
                minJitter = lastJitter
            }

            if (payload.rFactor > 0.0F) {
                if (rFactor > 0.0F) {
                    rFactor = (rFactor * reportCount + payload.rFactor * reportCountIncrement) /
                            (reportCount + reportCountIncrement)
                } else {
                    rFactor = payload.rFactor
                }

                // MoS
                mos = MediaUtil.computeMos(rFactor)
            }
        }

        if (createdAt > payload.startedAt) {
            createdAt = payload.startedAt
            report.startedAt = payload.startedAt
        }

        if (payload.startedAt + payload.duration > terminatedAt) {
            terminatedAt = payload.startedAt + payload.duration
        }
    }
}