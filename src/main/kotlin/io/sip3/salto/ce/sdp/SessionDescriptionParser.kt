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

package io.sip3.salto.ce.sdp

import org.restcomm.media.sdp.SessionDescription
import org.restcomm.media.sdp.SessionDescriptionParser

object SessionDescriptionParser {

    val REGEX_EXCLUDE = Regex("(?m)^m=image.*(?:\\r?\\n)?")
    val REGEX_TCP = Regex("(?m)^(a=candidate:.*)TCP(.*)\$")
    val REGEX_PRFLX = Regex("(?m)^(a=candidate:.*)prflx(.*)\$")

    fun parse(text: String?): SessionDescription {
        return SessionDescriptionParser.parse(
            text?.replace(REGEX_EXCLUDE, "")
                ?.replace(REGEX_TCP, "$1tcp$2")
                ?.replace(REGEX_PRFLX, "$1host$2")
        )
    }
}
