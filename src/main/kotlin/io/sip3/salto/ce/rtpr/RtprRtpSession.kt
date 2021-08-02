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

class RtprRtpSession(mediaControl: MediaControl) : RtprSession(mediaControl) {

    override val source = RtpReportPayload.SOURCE_RTP

    override fun assignAddresses(packet: Packet) {
        val sdpSession = mediaControl.sdpSession

        if (sdpSession.src.rtpId == packet.srcAddr.sdpSessionId()
            || sdpSession.dst.rtpId == packet.dstAddr.sdpSessionId()
        ) {
            srcAddr = packet.srcAddr
            dstAddr = packet.dstAddr
        } else {
            srcAddr = packet.dstAddr
            dstAddr = packet.srcAddr
        }
    }
}