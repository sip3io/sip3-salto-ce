/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles RtprSession
 */
@Instance
open class RtprSessionHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val REPORTS =  "_reports"
        const val BAD_REPORTS = "_bad-reports"
        const val BAD_REPORTS_FRACTION = "_bad-reports-fraction"

        const val DURATION = "_duration"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var minExpectedPackets = 100

    private var recordIpAddressesAttributes = false

    private lateinit var attributesRegistry: AttributesRegistry

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.let { config ->
            config.getInteger("min-expected-packets")?.let {
                minExpectedPackets = it
            }
        }

        config().getJsonObject("attributes")?.getBoolean("record-ip-addresses")?.let {
            recordIpAddressesAttributes = it
        }

        attributesRegistry = AttributesRegistry(vertx, config())

        vertx.eventBus().localConsumer<RtprSession>(RoutesCE.rtpr + "_session") { event ->
            try {
                val rtprSession = event.body()
                handle(rtprSession)
            } catch (e: Exception) {
                logger.error(e) { "RtprSessionHandler 'handle()' failed." }
            }
        }
    }

    open fun handle(session: RtprSession) {
        writeAttributes(session)

        val prefix = when (session.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
            else -> throw IllegalArgumentException("Unsupported RtprSession source: '${session.source}'")
        }
        writeToDatabase("${prefix}_index", session)
        calculateMetrics(prefix, session)
    }

    open fun writeAttributes(session: RtprSession) {
        val attributes = mutableMapOf<String, Any>().apply {
            val src = session.srcAddr
            put(Attributes.src_addr, if (recordIpAddressesAttributes) src.addr else "")
            src.host?.let { put(Attributes.src_host, it) }

            val dst = session.dstAddr
            put(Attributes.dst_addr, if (recordIpAddressesAttributes) dst.addr else "")
            dst.host?.let { put(Attributes.dst_host, it) }

            put(Attributes.bad_report_fraction, session.badReportFraction)
            put(Attributes.one_way, session.isOneWay)
            put(Attributes.duration, session.duration)
        }

        val prefix = when (session.source) {
            RtpReportPayload.SOURCE_RTP -> "rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtcp"
            else -> throw IllegalArgumentException("Unsupported RtprSession source: '${session.source}'")
        }
        attributesRegistry.handle(prefix, attributes)

        session.codecs.forEach { attributesRegistry.handle(prefix, Attributes.codec, it)}
    }

    open fun calculateMetrics(prefix: String, session: RtprSession) {
        session.apply {
            val attributes = mutableMapOf<String, Any>().apply {
                srcAddr.host?.let { put("src_host", it) }
                dstAddr.host?.let { put("dst_host", it) }
                codecs.firstOrNull()?.let { put("codec", it) }
            }

            Metrics.summary(prefix + REPORTS, attributes).record(reportCount.toDouble())
            Metrics.summary(prefix + BAD_REPORTS, attributes).record(badReportCount.toDouble())
            Metrics.summary(prefix + BAD_REPORTS_FRACTION, attributes).record(badReportFraction)

            Metrics.timer(prefix + DURATION, attributes).record(duration, TimeUnit.MILLISECONDS)
        }
    }

    open fun writeToDatabase(prefix: String, session: RtprSession) {
        val collection = prefix + "_" + timeSuffix.format(session.createdAt)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("created_at", session.createdAt)
                put("terminated_at", session.terminatedAt)

                val src = session.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = session.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("call_id", session.mediaControl.callId)
                put("caller", session.mediaControl.caller)
                put("callee", session.mediaControl.callee)

                put("codec", session.codecs.toList())
                put("duration", session.duration)

                put("report_count", session.reportCount)
                put("bad_report_count", session.badReportCount)
                put("bad_report_fraction", session.badReportFraction)

                put("one_way", session.isOneWay)

                val directions = mutableListOf<String>().apply {
                    if (session.forward != null) add("out")
                    if (session.reverse != null) add("in")
                }
                put("direction", directions)

                val reports = mutableListOf<RtpReportPayload>().apply {
                    session.forward?.report?.let { add(it) }
                    session.reverse?.report?.let { add(it) }
                }
                put("mos", reports.map { it.mos.toDouble() })
                put("r_factor", reports.map { it.rFactor.toDouble() })

                put("payload_type", reports.map { it.payloadType.toInt() })
                put("ssrc", reports.map { it.ssrc })

                put("packets", JsonObject().apply {
                    put("expected", reports.map { it.expectedPacketCount })
                    put("received", reports.map { it.receivedPacketCount })
                    put("lost", reports.map { it.lostPacketCount })
                    put("rejected", reports.map { it.rejectedPacketCount })
                })

                put("jitter", JsonObject().apply {
                    put("last", reports.map { it.lastJitter.toDouble() })
                    put("avg", reports.map { it.avgJitter.toDouble() })
                    put("min", reports.map { it.minJitter.toDouble() })
                    put("max", reports.map { it.maxJitter.toDouble() })
                })

                put("fraction_lost", reports.map { it.fractionLost.toDouble() })
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}