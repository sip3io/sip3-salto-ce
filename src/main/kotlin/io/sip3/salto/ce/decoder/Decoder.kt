/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

package io.sip3.salto.ce.decoder

import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.IpUtil
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import mu.KotlinLogging
import java.sql.Timestamp

/**
 * Decodes packets in SIP3 protocol
 */
class Decoder : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val HEADER_LENGTH = 4
    }

    private val packetsDecoded = Metrics.counter("packets_decoded", mapOf("proto" to "sip3"))

    override fun start() {
        vertx.eventBus().localConsumer<Buffer>(RoutesCE.sip3) { event ->
            try {
                val buffer = event.body()
                decode(buffer)
            } catch (e: Exception) {
                logger.error("Decoder 'decode()' failed.", e)
            }
        }
    }

    fun decode(buffer: Buffer) {
        var offset = HEADER_LENGTH

        val compressed = buffer.getByte(offset++)
        if (compressed.toInt() != 0) {
            throw NotImplementedError("Decoder failed to decode compressed payload. Make sure that you are using SIP3 Salto `Enterprise Edition`.")
        }

        while (offset < buffer.length()) {
            var packetOffset = offset
            // Packet Type
            val packetType = buffer.getByte(packetOffset)
            // Packet Version
            packetOffset += 1
            val packetVersion = buffer.getByte(packetOffset)

            if (packetType.toInt() != 1 || packetVersion.toInt() != 1) {
                throw NotImplementedError("Unknown SIP3 packet type or version. Type: $packetType, Version: $packetVersion")
            }

            var millis: Long? = null
            var nanos: Int? = null
            var srcAddr: ByteArray? = null
            var dstAddr: ByteArray? = null
            var srcPort: Int? = null
            var dstPort: Int? = null
            var protocolCode: Byte? = null
            var payload: ByteArray? = null

            // Packet Length
            packetOffset += 1
            val packetLength = buffer.getShort(packetOffset)

            packetOffset += 2
            while (packetOffset < offset + packetLength) {
                // Type
                val type = buffer.getByte(packetOffset)
                // Length
                packetOffset += 1
                val length = buffer.getShort(packetOffset) - 3
                // Value
                packetOffset += 2
                when (type.toInt()) {
                    1 -> millis = buffer.getLong(packetOffset)
                    2 -> nanos = buffer.getInt(packetOffset)
                    3 -> srcAddr = buffer.getBytes(packetOffset, packetOffset + length)
                    4 -> dstAddr = buffer.getBytes(packetOffset, packetOffset + length)
                    5 -> srcPort = buffer.getUnsignedShort(packetOffset)
                    6 -> dstPort = buffer.getUnsignedShort(packetOffset)
                    7 -> protocolCode = buffer.getByte(packetOffset)
                    8 -> payload = buffer.getBytes(packetOffset, packetOffset + length)
                }
                packetOffset += length
            }

            val packet = Packet().apply {
                this.timestamp = Timestamp(millis!!).apply { this.nanos = nanos!! }
                this.srcAddr = Address().apply {
                    addr = IpUtil.convertToString(srcAddr!!)
                    port = srcPort!!
                }
                this.dstAddr = Address().apply {
                    addr = IpUtil.convertToString(dstAddr!!)
                    port = dstPort!!
                }
                this.protocolCode = protocolCode!!
                this.payload = payload!!
            }

            packetsDecoded.increment()
            vertx.eventBus().send(RoutesCE.router, packet, USE_LOCAL_CODEC)

            offset += packetLength
        }
    }
}