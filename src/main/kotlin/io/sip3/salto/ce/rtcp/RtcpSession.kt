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

package io.sip3.salto.ce.rtcp

import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.IpUtil
import io.sip3.salto.ce.domain.Address
import java.sql.Timestamp

class RtcpSession {

    // Static session data
    lateinit var createdAt: Timestamp
    lateinit var dstAddr: Address
    lateinit var srcAddr: Address

    // Jitter
    var lastJitter = 0F

    lateinit var previousReport: RtcpReportBlock
    var lastNtpTimestamp: Long = 0
    var lastPacketTimestamp: Long = 0

    // SDP session
    var sdpSession: SdpSession? = null
    val srcSdpSessionId: Long by lazy {
        sessionId(srcAddr)
    }
    val dstSdpSessionId: Long by lazy {
        sessionId(dstAddr)
    }

    var rtcpReportCount = 0
    val cumulative: RtpReportPayload = RtpReportPayload().apply {
        source = RtpReportPayload.SOURCE_RTCP
        cumulative = true
    }

    private fun sessionId(address: Address): Long {
        return with(address) {
            val addAsLong = IpUtil.convertToInt(addr).toLong()
            val port = if (port % 2 == 0) {
                port
            } else {
                port - 1
            }
            ((addAsLong shl 32) or port.toLong())
        }
    }
}