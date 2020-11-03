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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.rtpr.RtprSession
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles media sessions
 */
@Instance(singleton = true)
open class MediaHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

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

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.let { config ->
            config.getBoolean("cumulative-metrics")?.let {
                cumulativeMetrics = it
            }
        }

        vertx.eventBus().localConsumer<RtprSession>(RoutesCE.media) { event ->
            try {
                val session = event.body()
                handle(session)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handle()' failed." }
            }
        }
    }

    open fun handle(session: RtprSession) {
        val report = session.report
        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }

        writeAttributes(report)
        writeToDatabase("${prefix}_index", session)

        if (cumulativeMetrics) {
            calculateMetrics(prefix, session)
        }
    }

    open fun writeAttributes(report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            put(Attributes.mos, report.mos)
            put(Attributes.r_factor, report.rFactor)
        }

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        vertx.eventBus().localRequest<Any>(RoutesCE.attributes, Pair(prefix, attributes))
    }

    open fun calculateMetrics(prefix: String, session: RtprSession) {
        val report = session.report
        val attributes = mutableMapOf<String, Any>().apply {
            session.srcAddr.host?.let { put("src_host", it) }
            session.dstAddr.host?.let { put("dst_host", it) }
            put("codec", report.codecName ?: report.payloadType)
        }

        with(report) {
            Metrics.summary(prefix + EXPECTED_PACKETS, attributes).record(expectedPacketCount.toDouble())
            Metrics.summary(prefix + LOST_PACKETS, attributes).record(lostPacketCount.toDouble())
            Metrics.summary(prefix + REJECTED_PACKETS, attributes).record(rejectedPacketCount.toDouble())

            Metrics.summary(prefix + JITTER, attributes).record(avgJitter.toDouble())

            Metrics.summary(prefix + R_FACTOR, attributes).record(rFactor.toDouble())
            Metrics.summary(prefix + MOS, attributes).record(mos.toDouble())

            Metrics.timer(prefix + DURATION, attributes).record(duration.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    open fun writeToDatabase(prefix: String, session: RtprSession) {
        val collection = prefix + "_" + timeSuffix.format(session.timestamp)
        val report = session.report

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("created_at", report.createdAt)
                put("started_at", report.startedAt)

                val src = session.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = session.dstAddr
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

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}