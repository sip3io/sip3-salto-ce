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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.rtpr.RtprSession
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Handles media sessions
 */
@Instance
open class MediaHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
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
        writeAttributes(report)

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp_index"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp_index"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        writeToDatabase(prefix, session)
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
        vertx.eventBus().localSend(RoutesCE.attributes, Pair(prefix, attributes))
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

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}