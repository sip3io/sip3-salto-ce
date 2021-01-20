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

package io.sip3.salto.ce.decoder

import io.sip3.commons.PacketTypes
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.IpUtil
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import mu.KotlinLogging
import java.sql.Timestamp

/**
 * Decodes packets in HEP2 and HEP3 protocols
 */
@Instance
class HepDecoder : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val HEP3_HEADER_LENGTH = 6
        const val HEP3_TYPE_SIP: Byte = 1
        const val HEP3_TYPE_RTCP: Byte = 5
    }

    private val packetsDecoded = Metrics.counter("packets_decoded", mapOf("proto" to "hep"))

    override fun start() {
        vertx.eventBus().localConsumer<Pair<Address, Buffer>>(RoutesCE.hep2) { event ->
            try {
                val (sender, buffer) = event.body()
                decodeHep2(sender, buffer)
            } catch (e: Exception) {
                logger.error("HepDecoder 'decodeHep2()' failed.", e)
            }
        }

        vertx.eventBus().localConsumer<Pair<Address, Buffer>>(RoutesCE.hep3) { event ->
            try {
                val (sender, buffer) = event.body()
                decodeHep3(sender, buffer)
            } catch (e: Exception) {
                logger.error("HepDecoder 'decodeHep3()' failed.", e)
            }
        }
    }

    fun decodeHep2(sender: Address, buffer: Buffer) {
        val packetLength = buffer.length()
        if (packetLength < 31) {
            logger.warn("HEP2 payload is to short: $packetLength")
            return
        }

        val srcPort: Int = buffer.getUnsignedShort(4)
        val dstPort: Int = buffer.getUnsignedShort(6)
        val srcAddr: ByteArray = buffer.getBytes(8, 12)
        val dstAddr: ByteArray = buffer.getBytes(12, 16)
        val seconds: Long = buffer.getUnsignedIntLE(16)
        val uSeconds: Long = buffer.getUnsignedIntLE(20)
        val payload: ByteArray = buffer.getBytes(28, packetLength)

        val packet = Packet().apply {
            this.timestamp = Timestamp(seconds * 1000 + uSeconds / 1000).apply { nanos += (uSeconds % 1000).toInt() }
            this.srcAddr = Address().apply {
                addr = IpUtil.convertToString(srcAddr)
                port = srcPort
            }
            this.dstAddr = Address().apply {
                addr = IpUtil.convertToString(dstAddr)
                port = dstPort
            }
            this.protocolCode = PacketTypes.SIP
            this.payload = payload
        }

        packetsDecoded.increment()
        vertx.eventBus().localSend(RoutesCE.router, Pair(sender, packet))
    }

    fun decodeHep3(sender: Address, buffer: Buffer) {
        var seconds: Long? = null
        var uSeconds: Long? = null
        var srcAddr: ByteArray? = null
        var dstAddr: ByteArray? = null
        var srcPort: Int? = null
        var dstPort: Int? = null
        var protocolType: Byte? = null
        var payload: ByteArray? = null

        var offset = HEP3_HEADER_LENGTH
        while (offset < buffer.length()) {
            // Type
            offset += 2
            val type = buffer.getShort(offset)
            // Length
            offset += 2
            val length = buffer.getShort(offset) - 6
            // Value
            offset += 2
            when (type.toInt()) {
                3 -> srcAddr = buffer.getBytes(offset + length - 4, offset + length)
                4 -> dstAddr = buffer.getBytes(offset + length - 4, offset + length)
                7 -> srcPort = buffer.getUnsignedShort(offset)
                8 -> dstPort = buffer.getUnsignedShort(offset)
                9 -> seconds = buffer.getUnsignedInt(offset)
                10 -> uSeconds = buffer.getUnsignedInt(offset)
                11 -> protocolType = buffer.getByte(offset)
                15 -> payload = buffer.getBytes(offset, offset + length)
            }
            offset += length
        }

        val packet = Packet().apply {
            this.timestamp = Timestamp(seconds!! * 1000 + uSeconds!! / 1000).apply { nanos += (uSeconds % 1000).toInt() }
            this.srcAddr = Address().apply {
                addr = IpUtil.convertToString(srcAddr!!)
                port = srcPort!!
            }
            this.dstAddr = Address().apply {
                addr = IpUtil.convertToString(dstAddr!!)
                port = dstPort!!
            }
            this.source = "hep3"
            when (protocolType) {
                HEP3_TYPE_SIP -> this.protocolCode = PacketTypes.SIP
                HEP3_TYPE_RTCP -> this.protocolCode = PacketTypes.RTCP
                else -> throw NotImplementedError("Unknown HEPv3 protocol type: $protocolType")
            }
            this.payload = payload!!
        }

        packetsDecoded.increment()
        vertx.eventBus().localSend(RoutesCE.router, Pair(sender, packet))
    }
}