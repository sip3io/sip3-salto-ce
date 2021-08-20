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

package io.sip3.salto.ce.util

import org.restcomm.media.sdp.fields.MediaDescriptionField

fun MediaDescriptionField.defineRtcpPort(isRtcpMux: Boolean): Int {
    return if (this.rtcp != null && this.rtcp?.port != this.port) {
        this.rtcp.port
    } else {
        if (this.rtcpMux != null && isRtcpMux) this.port else this.port + 1
    }
}

fun MediaDescriptionField.ptime(): Int? {
    return this.ptime?.time
}

fun MediaDescriptionField.address(): String {
    return this.connection.address
}