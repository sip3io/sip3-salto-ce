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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.restcomm.media.sdp.SessionDescription
import org.restcomm.media.sdp.SessionDescriptionParser

class SessionDescriptionParser(sdpFields: Collection<String> = emptySet()) {

    val logger = KotlinLogging.logger {}

    companion object {
        val DEFAULT_FIELDS = setOf(
            "m=audio",
            "c",
            "a=rtcp",
            "a=rtcp-mux",
            "a=ptime"
        )
    }

    private val fields = DEFAULT_FIELDS + sdpFields

    fun parse(text: String?): SessionDescription? {
        return try {
            text?.lineSequence()
                ?.filter { fields.any { prefix -> it.startsWith(prefix, ignoreCase = true) } }
                ?.joinToString("\n")
                .let { SessionDescriptionParser.parse(it) }
        } catch (e: Exception) {
            logger.debug(e) { "parse() failed. Text: $text" }
            null
        }
    }
}
