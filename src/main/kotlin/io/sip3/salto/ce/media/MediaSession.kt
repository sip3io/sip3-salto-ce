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

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.rtpr.RtprSession
import kotlin.math.min

class MediaSession(val srcAddr: Address, val dstAddr: Address, val mediaControl: MediaControl) {

    var createdAt: Long = 0L
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
        get() = forward.hasRtp() xor reverse.hasRtp()

    val hasUndefinedCodec: Boolean
        get() = codecNames.any { it.contains("UNDEFINED") }

    val missedPeer: Boolean
        get() = forward.missedPeer || reverse.missedPeer

    val mos: Double
        get() {
            return if (forward.mos == null) {
                reverse.mos ?: 1.0
            } else {
                reverse.mos?.let { min(forward.mos!!, it) } ?: forward.mos!!
            }
        }

    val rFactor: Double
        get() {
            return if (forward.rFactor == null) {
                reverse.rFactor ?: 1.0
            } else {
                reverse.rFactor?.let { min(forward.rFactor!!, it) } ?: forward.rFactor!!
            }
        }

    val reportCount: Int
        get() = forward.reportCount + reverse.reportCount

    val badReportCount: Int
        get() = forward.badReportCount + reverse.badReportCount

    val badReportFraction: Double
        get() {
            return if (reportCount > 0) {
                badReportCount / reportCount.toDouble()
            } else {
                0.0
            }
        }

    val duration: Long
        get() = terminatedAt - createdAt

    fun add(session: RtprSession) {
        when (session.source) {
            RtpReportPayload.SOURCE_RTP -> addRtpSession(session)
            RtpReportPayload.SOURCE_RTCP -> addRtcpSession(session)
        }

        if (createdAt > session.createdAt || createdAt == 0L) createdAt = session.createdAt
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
        if (session.dstAddr.addr == srcAddr.addr || session.srcAddr.addr == dstAddr.addr) {
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
            get() = rtp?.badReportCount ?: rtcp?.reportCount ?: 0

        val codecNames: Set<String>
            get() {
                return mutableSetOf<String>().apply {
                    rtp?.let { addAll(it.codecNames) }
                    rtcp?.let { addAll(it.codecNames) }
                }
            }

        val mos: Double?
            get() = rtp?.mos ?: rtcp?.mos

        val rFactor: Double?
            get() = rtp?.rFactor ?: rtcp?.rFactor

        val missedPeer: Boolean
            get() = rtp?.missedPeer ?: false

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
