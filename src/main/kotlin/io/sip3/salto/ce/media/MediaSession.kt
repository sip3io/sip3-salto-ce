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

class MediaSession(val srcAddr: Address, val dstAddr: Address) {

    var createdAt: Long = 0L
    var terminatedAt: Long = 0L

    val forward: MediaStream = MediaStream()
    val reverse: MediaStream = MediaStream()

    val isOneWay: Boolean
        get() = forward.isEmpty() || reverse.isEmpty()

    val reportCount: Int
        get() = forward.reportCount + reverse.reportCount

    val badReportCount: Int
        get() = forward.badReportCount + reverse.badReportCount

    lateinit var callId: String

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

    private fun addRtpSession(session: RtprSession) {
        if (session.srcAddr.sameAddrPort(srcAddr) || session.dstAddr.sameAddrPort(dstAddr)) {
            forward.addRtp(session)
        } else {
            reverse.addRtp(session)
        }
    }

    private fun addRtcpSession(session: RtprSession) {
        if (session.dstAddr.sameAddrPort(srcAddr) || session.srcAddr.sameAddrPort(dstAddr)) {
            forward.addRtcp(session)
        } else {
            reverse.addRtcp(session)
        }
    }

    private fun updateHost(addr: Address, session: RtprSession) {
        session.srcAddr
            .takeIf { it.sameAddrPort(addr) && it.host != null }
            ?.let { addr.host = it.host }

        session.dstAddr
            .takeIf { it.sameAddrPort(addr) && it.host != null }
            ?.let { addr.host = it.host }
    }

    private fun Address.sameAddrPort(other: Address): Boolean {
        return this.addr == other.addr && this.port == other.port
    }

    class MediaStream {

        var rtp: RtprSession? = null
        var rtcp: RtprSession? = null

        val reportCount: Int
            get() = (rtp?.reportCount ?: 0)

        val badReportCount: Int
            get() = (rtp?.badReportCount ?: 0)

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

        fun isEmpty(): Boolean {
            return rtp == null || rtcp == null
        }
    }
}
