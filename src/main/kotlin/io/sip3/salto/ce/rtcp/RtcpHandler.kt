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

package io.sip3.salto.ce.rtcp

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.MediaUtil.rtpSessionId
import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.util.remainingCapacity
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.nio.charset.Charset
import java.sql.Timestamp
import kotlin.experimental.and

/**
 * Handles RTCP packets
 */
@Instance
open class RtcpHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val MAX_VALID_JITTER = 10000
    }

    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private var instances: Int = 1

    private var sessions = mutableMapOf<Long, RtcpSession>()

    override fun start() {
        context.config().getJsonObject("media")?.getJsonObject("rtcp")?.let { config ->
            config.getLong("trim-to-size-delay")?.let {
                trimToSizeDelay = it
            }
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
        }

        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        vertx.setPeriodic(trimToSizeDelay) {
            sessions = MutableMapUtil.mutableMapOf(sessions)
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

        GlobalScope.launch(vertx.dispatcher()) {
            val index = vertx.sharedData().getLocalCounter(RoutesCE.rtcp).await()
            vertx.eventBus().localConsumer<Pair<Packet, SenderReport>>(RoutesCE.rtcp + "_${index.andIncrement.await()}") { event ->
                val (packet, senderReport) = event.body()
                try {
                    handleSenderReport(packet, senderReport)
                } catch (e: Exception) {
                    logger.error(e) { "RtcpHandler 'handleSenderReport()' failed. Sender report: ${JsonObject.mapFrom(senderReport)}" }
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
        val index = (packet.srcAddr.port * packet.dstAddr.port).hashCode() % instances
        vertx.eventBus().localSend(RoutesCE.rtcp + "_${index}", Pair(packet, senderReport))
    }

    private fun handleSenderReport(packet: Packet, senderReport: SenderReport) {
        val sessionId = rtpSessionId(packet.srcAddr.port, packet.dstAddr.port, senderReport.senderSsrc)

        val session = sessions.computeIfAbsent(sessionId) {
            RtcpSession().apply {
                createdAt = packet.timestamp
                dstAddr = packet.dstAddr
                srcAddr = packet.srcAddr
            }
        }

        senderReport.reportBlocks.forEach { report ->
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

                // TODO: Change default value in sip3-commons
                payloadType = -1

                lastJitter = session.lastJitter
                avgJitter = session.lastJitter
                minJitter = session.lastJitter
                maxJitter = session.lastJitter

                if (session.previousReport == null) {
                    lostPacketCount = report.cumulativePacketLost.toInt()

                    val packetCount = senderReport.senderPacketCount.toInt()
                    expectedPacketCount = packetCount + lostPacketCount
                    receivedPacketCount = packetCount

                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                } else {
                    lostPacketCount = (report.cumulativePacketLost - session.previousReport!!.cumulativePacketLost).toInt()

                    expectedPacketCount = (report.extendedSeqNumber - session.previousReport!!.extendedSeqNumber).toInt()
                    receivedPacketCount = expectedPacketCount - lostPacketCount

                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                }
            }

            session.previousReport = report
            vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(packet, payload))
        }

        session.lastPacketTimestamp = packet.timestamp.time
    }

    class RtcpSession {

        // Static session data
        lateinit var createdAt: Timestamp
        lateinit var dstAddr: Address
        lateinit var srcAddr: Address

        // Jitter
        var lastJitter = 0F

        var previousReport: RtcpReportBlock? = null
        var lastPacketTimestamp: Long = 0
    }

    class SenderReport {

        var reportBlockCount: Byte = 0
        var length: Int = 0
        var senderSsrc: Long = 0

        var ntpTimestampMsw: Long = 0
        var ntpTimestampLsw: Long = 0

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