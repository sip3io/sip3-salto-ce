/*
 * Copyright 2018-2026 SIP3.IO, Corp.
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

import io.sip3.commons.domain.media.MediaAddress
import io.sip3.salto.ce.domain.Address

fun MediaAddress.rtpAddress(): Address {
    return Address().apply {
        addr = this@rtpAddress.addr
        port = rtpPort
    }
}

fun MediaAddress.rtcpAddress(): Address {
    return Address().apply {
        addr = this@rtcpAddress.addr
        port = rtcpPort
    }
}
