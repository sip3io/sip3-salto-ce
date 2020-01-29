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

package io.sip3.salto.ce.util

import io.sip3.commons.util.IpUtil
import org.restcomm.media.sdp.fields.MediaDescriptionField

fun MediaDescriptionField.sdpSessionId(): Long {
    val addr = IpUtil.convertToInt(this.connection.address).toLong()
    val port = this.port.toLong()
    return (addr shl 32) or port
}