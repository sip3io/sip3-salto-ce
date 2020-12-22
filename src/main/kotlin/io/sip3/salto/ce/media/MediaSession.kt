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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.rtpr.RtprSession
import kotlin.math.abs

class MediaSession(val srcAddr: Address, val dstAddr: Address, val callId: String) {

    var createdAt: Long = System.currentTimeMillis()
    var terminatedAt: Long = 0L

    var aliveAt: Long = System.currentTimeMillis()

    val forward = MediaStream()
    val reverse = MediaStream()

    val codecName: String?
        get() = forward.codecName ?: reverse.codecName

    val isOneWay: Boolean
        get() = forward.hasRtp() xor reverse.hasRtp()

    val reportCount: Int
        get() = forward.reportCount + reverse.reportCount

    val badReportCount: Int
        get() = forward.badReportCount + reverse.badReportCount

    val createdAtDiff: Long?
        get() = diffOrNull(forward.rtp?.createdAt, reverse.rtp?.createdAt)

    val terminatedAtDiff: Long?
        get() = diffOrNull(forward.rtp?.terminatedAt, reverse.rtp?.terminatedAt)

    val durationDiff: Long?
        get() = diffOrNull(forward.rtpDuration, reverse.rtpDuration)

    val duration: Long
        get() = terminatedAt - createdAt

    fun add(session: RtprSession) {
        when (session.report.source) {
            RtpReportPayload.SOURCE_RTP -> addRtpSession(session)
            RtpReportPayload.SOURCE_RTCP -> addRtcpSession(session)
        }

        if (createdAt > session.createdAt) createdAt = session.createdAt
        if (terminatedAt < session.terminatedAt) terminatedAt = session.terminatedAt

        if (srcAddr.host == null) updateHost(srcAddr, session)
        if (dstAddr.host == null) updateHost(dstAddr, session)
    }

    fun hasMedia(): Boolean {
        return forward.hasMedia() || reverse.hasMedia()
    }

    private fun diffOrNull(a: Long?, b: Long?): Long? {
        if (a == null || b == null) {
            return null
        }

        return abs(a - b)
    }

    private fun addRtpSession(session: RtprSession) {
        if (session.srcAddr == srcAddr || session.dstAddr == dstAddr) {
            forward.addRtp(session)
        } else {
            reverse.addRtp(session)
        }
    }

    private fun addRtcpSession(session: RtprSession) {
        if (session.dstAddr == srcAddr || session.srcAddr == dstAddr) {
            forward.addRtcp(session)
        } else {
            reverse.addRtcp(session)
        }
    }

    private fun updateHost(addr: Address, session: RtprSession) {
        session.srcAddr
            .takeIf { it == addr && it.host != null }
            ?.let { addr.host = it.host }

        session.dstAddr
            .takeIf { it == addr && it.host != null }
            ?.let { addr.host = it.host }
    }

    class MediaStream {

        var rtp: RtprSession? = null
        var rtcp: RtprSession? = null

        val reportCount: Int
            get() = (rtp?.reportCount ?: 0)

        val badReportCount: Int
            get() = (rtp?.badReportCount ?: 0)

        val codecName: String?
            get() = (rtp?.report?.codecName)
                ?: (rtcp?.report?.codecName)

        val rtpDuration: Long?
            get() = rtp?.report?.duration?.toLong()

        fun addRtp(session: RtprSession) {
            if (rtp == null) {
                rtp = session
            } else {
                rtp!!.merge(session)
            }
        }

        fun addRtcp(session: RtprSession) {
            if (rtcp == null) {
                rtcp = session
            } else {
                rtcp!!.merge(session)
            }
        }

        fun hasRtp(): Boolean {
            return rtp == null
        }

        fun hasMedia(): Boolean {
            return rtp != null || rtcp != null
        }
    }
}
