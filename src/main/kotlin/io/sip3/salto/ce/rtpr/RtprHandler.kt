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

import io.netty.buffer.Unpooled
import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.DurationUtil
import io.sip3.salto.ce.util.MediaUtil.R0
import io.sip3.salto.ce.util.MediaUtil.computeMos
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Handles RTP reports
 */
@Instance
open class RtprHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val JITTER = "_jitter"
        const val R_FACTOR = "_r-factor"
        const val MOS = "_mos"
        const val EXPECTED_PACKETS = "_expected-packets"
        const val LOST_PACKETS = "_lost-packets"
        const val REJECTED_PACKETS = "_rejected-packets"

        const val DURATION = "_duration"

        const val UNDEFINED = "_undefined"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000
    private var cumulativeMetrics = true
    private var minExpectedPackets = 100
    private var rFactorThreshold: Float = 85F
    private var rFactorDistributions = TreeSet<Int>()
    private var durationDistributions = TreeMap<Long, String>()
    private var bulkPacketLimit = 1024

    private var instances: Int = 1

    private lateinit var mediaControls: PeriodicallyExpiringHashMap<String, MediaControl>
    private lateinit var rtp: PeriodicallyExpiringHashMap<String, RtprSession>
    private lateinit var rtcp: PeriodicallyExpiringHashMap<String, RtprSession>
    private lateinit var bulks: PeriodicallyExpiringHashMap<String, RtprBulk>

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.let { config ->
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
            config.getBoolean("cumulative-metrics")?.let {
                cumulativeMetrics = it
            }
            config.getInteger("min-expected-packets")?.let {
                minExpectedPackets = it
            }
            config.getFloat("r-factor-threshold")?.let {
                rFactorThreshold = it
            }
            config.getJsonArray("r-factor-distributions")?.forEach {
                rFactorDistributions.add(it as Int)
            }
            config.getJsonArray("duration-distributions")?.forEach {
                durationDistributions[DurationUtil.parseDuration(it as String).toMillis()] = it
            }
            config.getInteger("bulk-packet-limit")?.let {
                bulkPacketLimit = it
            }
        }
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        mediaControls = PeriodicallyExpiringHashMap.Builder<String, MediaControl>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, mediaControl -> mediaControl.timestamp + aggregationTimeout }
            .build(vertx)

        rtp = PeriodicallyExpiringHashMap.Builder<String, RtprSession>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, session -> session.terminatedAt + aggregationTimeout }
            .onExpire { _, session -> terminateRtprSession(session) }
            .build(vertx)

        rtcp = PeriodicallyExpiringHashMap.Builder<String, RtprSession>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, session -> session.terminatedAt + aggregationTimeout }
            .onExpire { _, session -> terminateRtprSession(session) }
            .build(vertx)

        bulks = PeriodicallyExpiringHashMap.Builder<String, RtprBulk>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, bulk -> bulk.updatedAt + aggregationTimeout }
            .onRemain { _, bulk ->
                if (bulk.expectedPackets >= bulkPacketLimit) {
                    writeToDatabase(bulk)
                    bulk.clear()
                }
            }
            .onExpire { _, bulk ->
                if (bulk.expectedPackets > 0) {
                    writeToDatabase(bulk)
                }
            }
            .build(vertx)

        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            val mediaControl = event.body()
            handleMediaControl(mediaControl)
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rtpr) { event ->
            try {
                val packet = event.body()
                handleRaw(packet)
            } catch (e: Exception) {
                logger.error(e) { "RtprHandler 'handleRaw()' failed." }
            }
        }

        vertx.eventBus().localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_rtcp") { event ->
            try {
                val (packet, report) = event.body()
                route(packet, report)
            } catch (e: Exception) {
                logger.error(e) { "RtprHandler 'route()' failed." }
            }
        }

        GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
            val index = vertx.sharedData().getLocalCounter(RoutesCE.rtpr).await()
            vertx.eventBus().localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_${index.andIncrement.await()}") { event ->
                try {
                    val (packet, report) = event.body()
                    handle(packet, report)
                } catch (e: Exception) {
                    logger.error(e) { "RtprHandler 'handle()' failed." }
                }
            }
        }
    }

    open fun handleMediaControl(mediaControl: MediaControl) {
        mediaControl.sdpSession.apply {
            mediaControls.put(src.rtpId, mediaControl)
            mediaControls.put(src.rtcpId, mediaControl)
            mediaControls.put(dst.rtpId, mediaControl)
            mediaControls.put(dst.rtcpId, mediaControl)
        }
    }

    open fun handleRaw(packet: Packet) {
        val report = RtpReportPayload().apply {
            val payload = Unpooled.wrappedBuffer(packet.payload)
            decode(payload)
        }

        route(packet, report)
    }

    open fun route(packet: Packet, report: RtpReportPayload) {
        val index = (packet.srcAddr.port + packet.dstAddr.port).hashCode() % instances
        vertx.eventBus().localSend(RoutesCE.rtpr + "_${index}", Pair(packet, report))
    }

    open fun handle(packet: Packet, report: RtpReportPayload) {
        val source = report.source
        val (prefix, sessions) = when (source) {
            RtpReportPayload.SOURCE_RTP -> Pair("rtpr_rtp", rtp)
            RtpReportPayload.SOURCE_RTCP -> Pair("rtpr_rtcp", rtcp)
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }

        val sessionId = packet.srcAddr.compositeKey(packet.dstAddr) { it.sdpSessionId() }
        var session = sessions.get(sessionId)
        if (session == null) {
            val mediaControl = mediaControls.get(packet.srcAddr.sdpSessionId())
                ?: mediaControls.get(packet.dstAddr.sdpSessionId())

            if (mediaControl != null) {
                session = RtprSession.create(source, mediaControl, packet).apply {
                    rFactorThreshold = this@RtprHandler.rFactorThreshold
                }
                sessions.put(sessionId, session)
            }
        }

        if (session != null) {
            updateWithMediaControl(report, session.mediaControl)
            session.add(packet, report)

            if (session.mediaControl.recording == null && session.source == RtpReportPayload.SOURCE_RTP) {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_update", session)
            }
        }

        val bulkId = "$prefix:${packet.srcAddr.sdpSessionId()}:${packet.dstAddr.sdpSessionId()}:${session?.callId ?: ""}"
        bulks.getOrPut(bulkId) { RtprBulk("${prefix}_raw", packet.srcAddr, packet.dstAddr) }.apply {
            add(report)
        }

        if (!cumulativeMetrics) {
            calculateMetrics(prefix, packet.srcAddr, packet.dstAddr, report)
        }
    }

    open fun updateWithMediaControl(report: RtpReportPayload, mediaControl: MediaControl) {
        report.callId = mediaControl.callId

        if (report.codecName != null) return

        val sdpSession = mediaControl.sdpSession
        if (report.source == RtpReportPayload.SOURCE_RTCP && report.duration == 0) {
            report.duration = report.expectedPacketCount * sdpSession.ptime

            // Recalculate `createdAt` for first report
            if (report.createdAt == report.reportedAt) {
                report.createdAt = report.createdAt - report.duration
            }
        }

        val isRtcp = report.source == RtpReportPayload.SOURCE_RTCP
        val codec = if (isRtcp) {
            sdpSession.codecs.firstOrNull()
        } else {
            sdpSession.codec(report.payloadType.toInt())
        }

        if (codec != null) {
            report.codecName = codec.name

            // Raw rFactor value
            val ppl = report.fractionLost * 100
            val ieEff = codec.ie + (95 - codec.ie) * ppl / (ppl + codec.bpl)

            report.rFactor = (R0 - ieEff)

            // MoS
            report.mos = computeMos(report.rFactor)
        }
    }

    open fun terminateRtprSession(session: RtprSession) {
        session.forward?.let { terminateRtprStream(it) }
        session.reverse?.let { terminateRtprStream(it) }

        vertx.eventBus().localSend(RoutesCE.rtpr + "_session", session)
    }

    open fun terminateRtprStream(stream: RtprStream) {
        if (cumulativeMetrics) {
            val prefix = when (stream.source) {
                RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
                RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
                else -> throw IllegalArgumentException("Unsupported RTP Report source: '${stream.source}'")
            }
            calculateMetrics(prefix, stream.srcAddr, stream.dstAddr, stream.report)
        }
    }

    open fun calculateMetrics(prefix: String, src: Address, dst: Address, report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            src.host?.let { put("src_host", it) }
            dst.host?.let { put("dst_host", it) }
            report.codecName?.let { put("codec", it) }

            if (report.expectedPacketCount >= minExpectedPackets) {
                put(Attributes.ranked, true)
            }
        }

        report.apply {
            Metrics.summary(prefix + EXPECTED_PACKETS, attributes).record(expectedPacketCount.toDouble())
            Metrics.summary(prefix + LOST_PACKETS, attributes).record(lostPacketCount.toDouble())
            Metrics.summary(prefix + REJECTED_PACKETS, attributes).record(rejectedPacketCount.toDouble())

            if (callId != null && codecName != null) {
                Metrics.timer(prefix + DURATION, attributes).record(duration.toLong(), TimeUnit.MILLISECONDS)
                Metrics.summary(prefix + JITTER, attributes).record(avgJitter.toDouble())
                Metrics.summary(prefix + MOS, attributes).record(mos.toDouble())

                rFactorDistributions.ceiling(rFactor.toInt())
                    ?.let { attributes[Attributes.distribution] = it }
                durationDistributions.ceilingKey(report.duration.toLong())
                    ?.let { attributes[Attributes.duration] = durationDistributions[it]!! }

                Metrics.summary(prefix + R_FACTOR, attributes).record(rFactor.toDouble())
            } else {
                Metrics.counter(prefix + UNDEFINED, attributes).increment()
            }
        }
    }

    open fun writeToDatabase(bulk: RtprBulk) {
        val firstReport = bulk.reports.first()
        val collection = bulk.prefix + "_" + timeSuffix.format(firstReport.createdAt)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("reported_at", firstReport.reportedAt)
                put("created_at", firstReport.createdAt)
                firstReport.callId?.let { put("call_id", firstReport) }

                val src = bulk.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = bulk.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                bulk.reports
                    .map { report ->
                        JsonObject().apply {
                            put("reported_at", report.reportedAt)
                            put("created_at", report.createdAt)

                            put("payload_type", report.payloadType.toInt())
                            put("ssrc", report.ssrc)
                            report.callId?.let { put("call_id", it) }
                            put("codec", report.codecName ?: "UNDEFINED(${report.payloadType})")
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
                        }
                    }
                    .also { put("reports", it) }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    inner class RtprBulk(val prefix: String, val srcAddr: Address, val dstAddr: Address) {

        var updatedAt = System.currentTimeMillis()
        val reports = mutableListOf<RtpReportPayload>()

        var expectedPackets = 0

        fun add(report: RtpReportPayload) {
            reports.add(report)
            expectedPackets += report.expectedPacketCount
        }

        fun clear() {
            reports.clear()
            expectedPackets = 0
        }
    }
}