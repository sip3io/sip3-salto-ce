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
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import okhttp3.internal.format

abstract class RtprSession(val mediaControl: MediaControl) {

    companion object {

        fun create(source: Byte, mediaControl: MediaControl, packet: Packet, rFactorThreshold: Float): RtprSession {
            return when (source) {
                RtpReportPayload.SOURCE_RTP -> RtprRtpSession(mediaControl)
                RtpReportPayload.SOURCE_RTCP -> RtprRtcpSession(mediaControl)
                else -> throw IllegalArgumentException("Unsupported source: $source")
            }.apply {
                assignAddresses(packet)
                this.rFactorThreshold = rFactorThreshold
            }
        }
    }

    var createdAt: Long = 0L
    var terminatedAt: Long = 0L

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    abstract val source: Byte
    var rFactorThreshold: Float? = null

    var forward: RtprStream? = null
    var reverse: RtprStream? = null

    val callId: String
        get() = mediaControl.callId

    val reportCount: Int
        get() = (forward?.reportCount ?: 0) + (reverse?.reportCount ?: 0)
    val badReportCount: Int
        get() = (forward?.badReportCount ?: 0) + (reverse?.badReportCount ?: 0)
    val badReportFraction: Double
        get() {
            return if (reportCount > 0) {
                badReportCount / reportCount.toDouble()
            } else {
                0.0
            }
        }

    val codecNames: Set<String>
        get() = mutableSetOf<String>().apply {
            forward?.codecNames?.let { addAll(it) }
            reverse?.codecNames?.let { addAll(it) }
        }

    val isOneWay: Boolean
        get() {
            if (source != RtpReportPayload.SOURCE_RTP) return false

            return (forward != null) xor (reverse != null)
        }

    val hasUndefinedCodec: Boolean
        get() = codecNames.any { it.contains("UNDEFINED") }

    val duration: Long
        get() = terminatedAt - createdAt

    abstract fun assignAddresses(packet: Packet)

    open fun add(packet: Packet, payload: RtpReportPayload) {
        if (packet.srcAddr.equals(srcAddr) || packet.dstAddr.equals(dstAddr)) {
            if (forward == null) forward = RtprStream(packet, rFactorThreshold)
            forward!!.add(payload)
        } else {
            if (reverse == null) reverse = RtprStream(packet, rFactorThreshold)
            reverse!!.add(payload)
        }

        listOfNotNull(forward?.createdAt, reverse?.createdAt)
            .minOrNull()
            ?.let { createdAt = it }

        listOfNotNull(forward?.terminatedAt, reverse?.terminatedAt)
            .maxOrNull()
            ?.let { terminatedAt = it }
    }
}