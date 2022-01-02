/*
 * Copyright 2018-2022 SIP3.IO, Corp.
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

import gov.nist.javax.sip.header.CSeq
import gov.nist.javax.sip.header.ExtensionHeaderImpl
import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.parser.*
import io.sip3.salto.ce.domain.Packet
import mu.KotlinLogging

/**
 * Parses SIP messages
 */
class SipMessageParser(val supportedMethods: Set<String>, val extensionHeaders: Set<String> = emptySet()) {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val CR: Byte = 0x0d
        const val LF: Byte = 0x0a
    }

    fun parse(packet: Packet): List<Pair<Packet, SIPMessage>> {
        val result = mutableListOf<Pair<Packet, SIPMessage>>()

        try {
            parse(packet, result)
        } catch (e: Exception) {
            logger.debug(e) { "SipMessageParser `parse()` failed.\n $packet" }
        }

        return result
    }

    private fun parse(packet: Packet, accumulator: MutableList<Pair<Packet, SIPMessage>>) {
        val payload = packet.payload
        var offset = 0

        // Skip blank lines
        while (isCrLf(offset, payload)) {
            offset += 2
        }

        // Create new parser
        val parser = StringMessageParser()

        // Parse message headers
        val message = parser.parseSIPMessage(payload, false, false, null) ?: return
        offset += message.size

        // Parse message content if needed
        message.contentLengthHeader?.contentLength?.let { length ->
            if (length > 0) {
                message.setMessageContent(payload.copyOfRange(offset, offset + length))
                offset += length
            }
        }

        // Skip blank lines
        while (isCrLf(offset, payload)) {
            offset += 2
        }

        // Skip or save message
        if (!parser.skipMessage) {
            if (payload.size > offset) {
                packet.payload = payload.copyOfRange(0, offset)
            }
            accumulator.add(Pair(packet, message))
        }

        // Check if there is more than a single message
        if (payload.size > offset) {
            val pkt = Packet().apply {
                this.createdAt = packet.createdAt
                this.nanos = packet.nanos
                this.srcAddr = packet.srcAddr
                this.dstAddr = packet.dstAddr
                this.protocolCode = packet.protocolCode
                this.payload = payload.copyOfRange(offset, payload.size)
            }
            parse(pkt, accumulator)
        }
    }

    private fun isCrLf(offset: Int, payload: ByteArray): Boolean {
        if (payload.size <= offset + 1) {
            return false
        }
        return payload[offset] == CR && payload[offset + 1] == LF
    }

    inner class StringMessageParser() : StringMsgParser() {

        var skipMessage = false

        override fun processFirstLine(firstLine: String?, parseExceptionListener: ParseExceptionListener?, msgBuffer: ByteArray?): SIPMessage {
            val message = super.processFirstLine(firstLine, parseExceptionListener, msgBuffer)
            if (message is SIPRequest) {
                skipMessage = !supportedMethods.contains(message.method)
            }
            return message
        }

        override fun processHeader(header: String?, message: SIPMessage, parseExceptionListener: ParseExceptionListener?, rawMessage: ByteArray) {
            if (header.isNullOrEmpty()) {
                return
            }

            val name = Lexer.getHeaderName(header)

            val hdr = when (name.lowercase()) {
                // These headers may or will be used in the SIP3 aggregation logic
                "content-length", "l" -> ContentLengthParser(header + "\n").parse()
                "cseq" -> {
                    CSeqParser(header + "\n").parse().also { cseq ->
                        skipMessage = !supportedMethods.contains((cseq as CSeq).method)
                    }
                }
                "to", "t" -> if (!skipMessage) ToParser(header + "\n").parse() else null
                "from", "f" -> if (!skipMessage) FromParser(header + "\n").parse() else null
                "via", "v" -> if (!skipMessage) ViaParser(header + "\n").parse() else null
                "contact", "m" -> if (!skipMessage) ContactParser(header + "\n").parse() else null
                "content-type", "c" -> if (!skipMessage) ContentTypeParser(header + "\n").parse() else null
                "call-id", "i" -> if (!skipMessage) CallIDParser(header + "\n").parse() else null
                "route" -> if (!skipMessage) RouteParser(header + "\n").parse() else null
                "record-route" -> if (!skipMessage) RecordRouteParser(header + "\n").parse() else null
                "max-forwards" -> if (!skipMessage) MaxForwardsParser(header + "\n").parse() else null
                "expires" -> if (!skipMessage) ExpiresParser(header + "\n").parse() else null
                else -> {
                    // These headers won't be used in the SIP3 aggregation logic
                    // So we can just attach them as generic `Extension` headers
                    if (!skipMessage && extensionHeaders.contains(name)) {
                        ExtensionHeaderImpl().apply {
                            this.name = name
                            this.value = Lexer.getHeaderValue(header)?.trim()
                        }
                    } else {
                        null
                    }
                }
            }

            hdr?.let { message.attachHeader(it, false) }
        }
    }
}