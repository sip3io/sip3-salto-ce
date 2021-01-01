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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.header.ExtensionHeaderImpl
import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.*
import io.sip3.salto.ce.domain.Packet
import mu.KotlinLogging

/**
 * Parses SIP messages
 */
class SipMessageParser(val extensionHeaders: Set<String> = emptySet()) {

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

        while (isCrLf(offset, payload)) {
            offset += 2
        }

        val message = StringMessageParser().parseSIPMessage(payload, false, false, null) ?: return
        offset += message.size
        message.contentLengthHeader?.contentLength?.let { length ->
            if (length > 0) {
                message.setMessageContent(payload.copyOfRange(offset, offset + length))
                offset += length
            }
        }

        if (payload.size > offset) {
            packet.payload = payload.copyOfRange(0, offset)
        }
        accumulator.add(Pair(packet, message))

        while (isCrLf(offset, payload)) {
            offset += 2
        }

        if (payload.size > offset) {
            val pkt = Packet().apply {
                this.timestamp = packet.timestamp
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

    inner class StringMessageParser : StringMsgParser() {

        override fun processHeader(header: String?, message: SIPMessage, parseExceptionListener: ParseExceptionListener?, rawMessage: ByteArray) {
            if (header.isNullOrEmpty()) {
                return
            }

            val headerName = Lexer.getHeaderName(header)

            val hdr = when (headerName.toLowerCase()) {
                // These headers may or will be used in the SIP3 aggregation logic
                "to", "t" -> ToParser(header + "\n").parse()
                "from", "f" -> FromParser(header + "\n").parse()
                "cseq" -> CSeqParser(header + "\n").parse()
                "via", "v" -> ViaParser(header + "\n").parse()
                "contact", "m" -> ContactParser(header + "\n").parse()
                "content-type", "c" -> ContentTypeParser(header + "\n").parse()
                "content-length", "l" -> ContentLengthParser(header + "\n").parse()
                "call-id", "i" -> CallIDParser(header + "\n").parse()
                "route" -> RouteParser(header + "\n").parse()
                "record-route" -> RecordRouteParser(header + "\n").parse()
                "max-forwards" -> MaxForwardsParser(header + "\n").parse()
                "expires" -> ExpiresParser(header + "\n").parse()
                else -> {
                    // These headers won't be used in the SIP3 aggregation logic
                    // So we can just attach them as generic `Extension` headers
                    if (extensionHeaders.contains(headerName)) {
                        ExtensionHeaderImpl().apply {
                            name = headerName
                            value = Lexer.getHeaderValue(header)?.trim()
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