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

import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.rtpr.RtprSession
import kotlin.math.min

class MediaSession(val srcAddr: Address, val dstAddr: Address, val callId: String) {

    var createdAt: Long = System.currentTimeMillis()
    var terminatedAt: Long = 0L
    var updatedAt: Long = System.currentTimeMillis()

    val forward = MediaStream()
    val reverse = MediaStream()

    val codecNames: Set<String>
        get() {
            return mutableSetOf<String>().apply {
                addAll(forward.codecNames)
                addAll(reverse.codecNames)
            }
        }

    val isOneWay: Boolean
        get() {
            return forward.hasRtp() xor reverse.hasRtp()
        }

    val hasUndefinedCodec: Boolean
        get() {
            return codecNames.any { it.contains("UNDEFINED") }
        }

    val mos: Double
        get() {
            return if (forward.mos == null) {
                reverse.mos ?: 1F
            } else {
                reverse.mos?.let { min(forward.mos!!, it) } ?: forward.mos!!
            }.toDouble()
        }

    val rFactor: Double
        get() {
            return if (forward.rFactor == null) {
                reverse.rFactor ?: 1F
            } else {
                reverse.rFactor?.let { min(forward.rFactor!!, it) } ?: forward.rFactor!!
            }.toDouble()
        }

    val reportCount: Int
        get() {
            return forward.reportCount + reverse.reportCount
        }

    val badReportCount: Int
        get() {
            return forward.badReportCount + reverse.badReportCount
        }

    val badReportFraction: Double
        get() {
            return if (reportCount > 0) {
                badReportCount / reportCount.toDouble()
            } else {
                1.0
            }
        }

    val duration: Long
        get() {
            return terminatedAt - createdAt
        }

    fun add(session: RtprSession) {
        when (session.report.source) {
            RtpReportPayload.SOURCE_RTP -> addRtpSession(session)
            RtpReportPayload.SOURCE_RTCP -> addRtcpSession(session)
        }

        if (createdAt > session.createdAt) createdAt = session.createdAt
        if (terminatedAt < session.terminatedAt) terminatedAt = session.terminatedAt

        if (srcAddr.host == null) updateHost(srcAddr, session)
        if (dstAddr.host == null) updateHost(dstAddr, session)

        updatedAt = System.currentTimeMillis()
    }

    fun hasMedia(): Boolean {
        return forward.hasMedia() || reverse.hasMedia()
    }

    private fun addRtpSession(session: RtprSession) {
        if (session.srcAddr == srcAddr || session.dstAddr == dstAddr) {
            forward.addRtp(session)
        } else {
            reverse.addRtp(session)
        }
    }

    private fun addRtcpSession(session: RtprSession) {
        if (session.dstAddr.addr == srcAddr.addr || session.dstAddr.addr == srcAddr.addr) {
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
            get() {
                return rtp?.reportCount ?: rtcp?.reportCount ?: 0
            }

        val badReportCount: Int
            get() {
                return rtp?.badReportCount ?: rtcp?.reportCount ?: 0
            }

        val codecNames: Set<String>
            get() {
                return mutableSetOf<String>().apply {
                    rtp?.let { addAll(it.codecNames) }
                    rtcp?.let { addAll(it.codecNames) }
                }
            }

        val mos: Float?
            get() {
                return rtp?.mos ?: rtcp?.mos
            }
        val rFactor: Float?
            get() {
                return rtp?.rFactor ?: rtcp?.rFactor
            }

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
