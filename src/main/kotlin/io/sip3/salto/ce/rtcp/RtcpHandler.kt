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

package io.sip3.salto.ce.rtcp

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.MediaUtil.rtpSessionId
import io.sip3.commons.util.remainingCapacity
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.apache.commons.net.ntp.TimeStamp
import java.nio.charset.Charset
import java.sql.Timestamp
import kotlin.experimental.and

/**
 * Handles RTCP packets
 */
@Instance(singleton = true)
open class RtcpHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val MAX_VALID_JITTER = 10000
    }

    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private val sessions = mutableMapOf<Long, RtcpSession>()

    override fun start() {
        context.config().getJsonObject("media")?.getJsonObject("rtcp")?.let { config ->
            config.getLong("expiration-delay")?.let { expirationDelay = it }
            config.getLong("aggregation-timeout")?.let { aggregationTimeout = it }
        }

        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()
            sessions.filterValues { it.lastPacketTimestamp + aggregationTimeout < now }
                    .forEach { (sessionId, _) ->
                        sessions.remove(sessionId)
                    }
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rtcp) { event ->
            val packet = event.body()
            when (packet.source) {
                "sip3" -> {
                    try {
                        handleRaw(packet)
                    } catch (e: Exception) {
                        logger.error(e) { "RtcpHandler 'handleRaw()' failed." }
                    }
                }
                "hep3" -> {
                    try {
                        handleHep(packet)
                    } catch (e: Exception) {
                        logger.error(e) { "RtcpHandler 'handleHep()' failed." }
                    }
                }
            }
        }
    }


    open fun handleRaw(packet: Packet) {
        val payload = Unpooled.wrappedBuffer(packet.payload)

        while (payload.remainingCapacity() > 4) {
            val offset = payload.readerIndex()

            val headerByte = payload.readByte()
            val payloadType = payload.readUnsignedByte().toInt()
            val reportLength = payload.readUnsignedShort() * 4

            // Return if report is not fully readable
            if (offset + reportLength > payload.capacity()) {
                return
            }

            when (payloadType) {
                // SR: Sender Report RTCP Packet
                200 -> {
                    try {
                        val report = readSenderReport(headerByte, payload)
                        onSenderReport(packet, report)
                    } catch (e: Exception) {
                        logger.trace(e) { "RtcpHandler `readSenderReport()` or `onSenderReport()` failed." }
                    }
                }
                else -> {
                    // Skip reports:
                    // 201 RR: Receiver Report
                    // 202 SDES: Source Description
                    // 203 BYE: Goodbye
                    // 204 APP: Application-Defined
                    // Undefined RTCP packet
                }
            }

            val nextIndex = offset + reportLength + 4
            if (nextIndex <= payload.capacity()) {
                // Move reader index to next RTCP report in packet
                payload.readerIndex(nextIndex)
            } else {
                // Stop RTCP packet processing
                logger.debug {
                    val src = with(packet.srcAddr) { "${addr}:${port}" }
                    val dst = with(packet.dstAddr) { "${addr}:${port}" }
                    val packetPayload = ByteBufUtil.prettyHexDump(payload.readerIndex(0))
                    "Invalid RTCP packet. Source: $src, Destination: $dst, Packet payload:\n $packetPayload"
                }
                break
            }
        }
    }

    open fun handleHep(packet: Packet) {
        val json = JsonObject(packet.payload.toString(Charset.defaultCharset()))
        json.getJsonObject("sender_information")?.let { senderInfo ->
            if (senderInfo.getLong("ntp_timestamp_sec") != 0L && senderInfo.getLong("ntp_timestamp_usec") != 0L) {
                val report = SenderReport().apply {
                    reportBlockCount = json.getInteger("report_count").toByte()
                    // Sender SSRC
                    senderSsrc = json.getLong("ssrc")

                    // NTP Timestamp: Most and Least significant words
                    ntpTimestampMsw = senderInfo.getLong("ntp_timestamp_sec")
                    ntpTimestampLsw = senderInfo.getLong("ntp_timestamp_usec")
                    // Sender's packet count
                    senderPacketCount = senderInfo.getLong("packets")

                    // Reports
                    json.getJsonArray("report_blocks")
                            .forEach { blockReport ->
                                blockReport as JsonObject
                                reportBlocks.add(RtcpReportBlock().apply {
                                    ssrc = blockReport.getLong("source_ssrc")
                                    fractionLost = blockReport.getInteger("fraction_lost").toShort()
                                    cumulativePacketLost = blockReport.getLong("packets_lost")
                                    extendedSeqNumber = blockReport.getLong("highest_seq_no")
                                    interarrivalJitter = blockReport.getLong("ia_jitter")
                                    lsrTimestamp = blockReport.getLong("lsr")
                                })
                            }
                }

                onSenderReport(packet, report)
            }
        }
    }


    private fun readSenderReport(headerByte: Byte, buffer: ByteBuf): SenderReport {
        return SenderReport().apply {
            reportBlockCount = headerByte.and(31)
            // Sender SSRC
            senderSsrc = buffer.readUnsignedInt()
            // NTP Timestamp: Most and Least significant words
            ntpTimestampMsw = buffer.readUnsignedInt()
            ntpTimestampLsw = buffer.readUnsignedInt()
            // RTP Timestamp
            buffer.skipBytes(4)
            // Sender's packet count
            senderPacketCount = buffer.readUnsignedInt()
            // Sender's octet count
            buffer.skipBytes(4)

            // Reports
            repeat(reportBlockCount.toInt()) {
                reportBlocks.add(RtcpReportBlock().apply {
                    // SSRC of sender
                    ssrc = buffer.readUnsignedInt()
                    // Fraction lost and Cumulative packet lost
                    buffer.readUnsignedInt().let { value ->
                        fractionLost = ((value and 0xF000) shr 24).toShort()
                        cumulativePacketLost = value and 0x0FFF
                    }
                    // Extended sequence number
                    extendedSeqNumber = buffer.readUnsignedInt()
                    // Interarrival Jitter
                    interarrivalJitter = buffer.readUnsignedInt()
                    // Last SR Timestamp
                    lsrTimestamp = buffer.readUnsignedInt()
                    // Delay since last SR
                    buffer.skipBytes(4)
                })
            }
        }
    }

    private fun onSenderReport(packet: Packet, senderReport: SenderReport) {
        senderReport.reportBlocks.forEach { report ->
            val sessionId = rtpSessionId(packet.srcAddr.port, packet.dstAddr.port, senderReport.senderSsrc)
            var isNewSession = false

            val session = sessions.computeIfAbsent(sessionId) {
                isNewSession = true
                RtcpSession().apply {
                    createdAt = packet.timestamp
                    dstAddr = packet.dstAddr
                    srcAddr = packet.srcAddr
                    this.lastNtpTimestamp = senderReport.ntpTimestamp
                }
            }

            // If interarrival jitter is greater than maximum, current jitter is bad
            if (report.interarrivalJitter < MAX_VALID_JITTER) {
                session.lastJitter = report.interarrivalJitter.toFloat()
            }

            val payload = RtpReportPayload().apply {
                createdAt = System.currentTimeMillis()
                startedAt = if (session.lastPacketTimestamp > 0) {
                    session.lastPacketTimestamp
                } else {
                    createdAt
                }

                source = RtpReportPayload.SOURCE_RTCP
                this.ssrc = report.ssrc

                lastJitter = session.lastJitter
                avgJitter = session.lastJitter
                minJitter = session.lastJitter
                maxJitter = session.lastJitter

                if (isNewSession) {
                    lostPacketCount = report.cumulativePacketLost.toInt()

                    val packetCount = senderReport.senderPacketCount.toInt()
                    expectedPacketCount = packetCount + lostPacketCount
                    receivedPacketCount = packetCount

                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                } else {
                    lostPacketCount = (report.cumulativePacketLost - session.previousReport.cumulativePacketLost).toInt()

                    expectedPacketCount = (report.extendedSeqNumber - session.previousReport.extendedSeqNumber).toInt()
                    receivedPacketCount = expectedPacketCount - lostPacketCount

                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                    duration = (senderReport.ntpTimestamp - session.lastNtpTimestamp).toInt()
                }
            }

            session.previousReport = report
            session.lastNtpTimestamp = senderReport.ntpTimestamp
            session.lastPacketTimestamp = packet.timestamp.time

            vertx.eventBus().localRequest<Any>(RoutesCE.rtpr + "_rtcp", Pair(packet, payload))
        }
    }

    class RtcpSession {

        // Static session data
        lateinit var createdAt: Timestamp
        lateinit var dstAddr: Address
        lateinit var srcAddr: Address

        // Jitter
        var lastJitter = 0F

        lateinit var previousReport: RtcpReportBlock
        var lastNtpTimestamp: Long = 0
        var lastPacketTimestamp: Long = 0
    }

    class SenderReport {

        var reportBlockCount: Byte = 0
        var length: Int = 0
        var senderSsrc: Long = 0

        var ntpTimestampMsw: Long = 0
        var ntpTimestampLsw: Long = 0
        val ntpTimestamp by lazy {
            TimeStamp("${ntpTimestampMsw.toString(16)}.${ntpTimestampLsw.toString(16)}").time
        }

        var senderPacketCount: Long = 0

        var reportBlocks = mutableListOf<RtcpReportBlock>()
    }

    class RtcpReportBlock {

        var ssrc: Long = 0
        var fractionLost: Short = 0
        var cumulativePacketLost: Long = 0
        var extendedSeqNumber: Long = 0
        var interarrivalJitter: Long = 0

        var lsrTimestamp: Long = 0
    }
}