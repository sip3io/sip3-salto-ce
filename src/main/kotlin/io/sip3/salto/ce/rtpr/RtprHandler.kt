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

package io.sip3.salto.ce.rtpr

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import io.netty.buffer.Unpooled
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.format
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

open class RtprHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX_RTP = "rtpr_rtp"
        const val PREFIX_RTCP = "rtpr_rtcp"

        const val JITTER = "_jitter"
        const val R_FACTOR = "_r-factor"
        const val MOS = "_mos"
        const val EXPECTED_PACKETS = "_expected-packets"
        const val LOST_PACKETS = "_lost-packets"
        const val REJECTED_PACKETS = "_rejected-packets"

        const val DURATION = "_duration"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rtpr) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("RtprHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(packet: Packet) {
        val payload = Unpooled.wrappedBuffer(packet.payload)
        val report = RtpReportPayload().apply { decode(payload) }
        val prefix = prefix(report.source)

        writeToDatabase(prefix, packet, report)
        calculateMetrics(prefix, packet, report)
    }

    open fun prefix(source: Byte): String {
        return when (source) {
            RtpReportPayload.SOURCE_RTP -> PREFIX_RTP
            RtpReportPayload.SOURCE_RTCP -> PREFIX_RTCP
            else -> throw IllegalArgumentException("Unsupported RTP report source: '${source}'")
        }
    }

    open fun calculateMetrics(prefix: String, packet: Packet, report: RtpReportPayload) {
        // Skip cumulative reports
        if (report.cumulative) {
            return;
        }

        val srcHost = packet.srcAddr.host
        val dstHost = packet.dstAddr.host

        report.apply {
            val codecTag = codecName ?: payloadType.toString()
            record(srcHost, dstHost, codecTag, prefix + EXPECTED_PACKETS, expectedPacketCount)
            record(srcHost, dstHost, codecTag, prefix + LOST_PACKETS, lostPacketCount)
            record(srcHost, dstHost, codecTag, prefix + REJECTED_PACKETS, rejectedPacketCount)

            record(srcHost, dstHost, codecTag, prefix + JITTER, avgJitter)

            record(srcHost, dstHost, codecTag, prefix + R_FACTOR, rFactor)
            record(srcHost, dstHost, codecTag, prefix + MOS, mos)

            Timer.builder(prefix + DURATION)
                    .apply {
                        srcHost?.let { tag("src_host", it) }
                        dstHost?.let { tag("dst_host", it) }
                        tag("codec", codecTag)
                    }
                    .register(Metrics.globalRegistry)
                    .record(duration.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    open fun record(srcHost: String?, dstHost: String?, codecTag: String, name: String, value: Number) {
        DistributionSummary.builder(name)
                .apply {
                    srcHost?.let { tag("src_host", it) }
                    dstHost?.let { tag("dst_host", it) }
                    tag("codec", codecTag)
                }
                .register(Metrics.globalRegistry)
                .record(value.toDouble())
    }

    open fun writeToDatabase(prefix: String, packet: Packet, report: RtpReportPayload) {
        val collection = if (report.cumulative) {
            "${prefix}_index_" + timeSuffix.format(packet.timestamp)
        } else {
            "${prefix}_raw_" + timeSuffix.format(packet.timestamp)
        }

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                put("created_at", report.createdAt)
                put("started_at", report.startedAt)

                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("payload_type", report.payloadType.toInt())
                put("ssrc", report.ssrc)
                report.callId?.let { put("call_id", it) }
                report.codecName?.let { put("codec_name", it) }
                put("duration", report.duration)

                put("expected_packet_count", report.expectedPacketCount)
                put("received_packet_count", report.receivedPacketCount)
                put("lost_packet_count", report.lostPacketCount)
                put("rejected_packet_count", report.rejectedPacketCount)

                put("last_jitter", report.lastJitter.toDouble())
                put("avg_jitter", report.avgJitter.toDouble())
                put("min_jitter", report.minJitter.toDouble())
                put("max_jitter", report.maxJitter.toDouble())

                put("r_factor", report.rFactor.toDouble())
                put("mos", report.mos.toDouble())
                put("fraction_lost", report.fractionLost.toDouble())
            })
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}