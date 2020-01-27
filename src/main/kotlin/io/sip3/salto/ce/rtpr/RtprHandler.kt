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

import io.netty.buffer.Unpooled
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles RTP reports
 */
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
    private var cumulativeMetrics = true

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("rtp-r")?.getBoolean("cumulative-metrics")?.let {
            cumulativeMetrics = it
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

        if (report.cumulative) {
            writeToDatabase("${prefix}_index", packet, report)
        } else {
            writeToDatabase("${prefix}_raw", packet, report)
        }

        if (report.cumulative == cumulativeMetrics) {
            calculateMetrics(prefix, packet, report)
        }
    }

    open fun prefix(source: Byte): String {
        return when (source) {
            RtpReportPayload.SOURCE_RTP -> PREFIX_RTP
            RtpReportPayload.SOURCE_RTCP -> PREFIX_RTCP
            else -> throw IllegalArgumentException("Unsupported RTP report source: '${source}'")
        }
    }

    open fun calculateMetrics(prefix: String, packet: Packet, report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            packet.srcAddr.host?.let { put("src_host", it) }
            packet.dstAddr.host?.let { put("dst_host", it) }
            put("codec", report.codecName ?: report.payloadType)
        }

        report.apply {
            Metrics.summary(prefix + EXPECTED_PACKETS, attributes).record(expectedPacketCount.toDouble())
            Metrics.summary(prefix + LOST_PACKETS, attributes).record(lostPacketCount.toDouble())
            Metrics.summary(prefix + REJECTED_PACKETS, attributes).record(rejectedPacketCount.toDouble())

            Metrics.summary(prefix + JITTER, attributes).record(avgJitter.toDouble())

            Metrics.summary(prefix + R_FACTOR, attributes).record(rFactor.toDouble())
            Metrics.summary(prefix + MOS, attributes).record(mos.toDouble())

            Metrics.timer(prefix + DURATION, attributes).record(duration.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    open fun writeToDatabase(prefix: String, packet: Packet, report: RtpReportPayload) {
        val collection = prefix + "_" + timeSuffix.format(packet.timestamp)

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

                put("packets", JsonObject().apply {
                    put("expected", report.expectedPacketCount)
                    put("received", report.receivedPacketCount)
                    put("lost", report.lostPacketCount)
                    put("rejected", report.rejectedPacketCount)
                })

                put("jitter", JsonObject().apply {
                    put("last", report.lastJitter.toDouble())
                    put("avg", report.avgJitter.toDouble())
                    put("min", report.minJitter.toDouble())
                    put("max", report.maxJitter.toDouble())
                })

                put("r_factor", report.rFactor.toDouble())
                put("mos", report.mos.toDouble())
                put("fraction_lost", report.fractionLost.toDouble())
            })
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}