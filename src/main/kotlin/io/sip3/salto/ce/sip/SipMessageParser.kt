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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import mu.KotlinLogging

class SipMessageParser {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val CR: Byte = 0x0d
        const val LF: Byte = 0x0a
    }

    fun parse(payload: ByteArray): List<SIPMessage> {
        val result = mutableListOf<SIPMessage>()

        try {
            parse(payload, result)
        } catch (e: Exception) {
            logger.debug(e) { "SipMessageParser `parse()` failed.\n $payload" }
        }

        return result
    }

    private fun parse(payload: ByteArray, accumulator: MutableList<SIPMessage>) {
        var offset = 0
        while (isCrLf(offset, payload)) {
            offset += 2
        }

        val message = StringMsgParser().parseSIPMessage(payload, false, false, null) ?: return
        offset += message.size
        message.contentLengthHeader
                ?.contentLength
                ?.let { length ->
                    if (length > 0) {
                        message.setMessageContent(payload.copyOfRange(offset, offset + length))
                        offset += length
                    }
                }

        accumulator.add(message)

        while (isCrLf(offset, payload)) {
            offset += 2
        }
        if (payload.size > offset) {
            parse(payload.copyOfRange(offset, payload.size), accumulator)
        }
    }

    private fun isCrLf(offset: Int, payload: ByteArray): Boolean {
        if (payload.size <= offset + 1) {
            return false
        }
        return payload[offset] == CR && payload[offset + 1] == LF
    }
}
