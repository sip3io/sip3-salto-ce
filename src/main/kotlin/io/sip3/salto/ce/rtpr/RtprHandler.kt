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

package io.sip3.salto.ce.rtpr

import io.netty.buffer.Unpooled
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.MediaUtil.rtpSessionId
import io.sip3.commons.util.MediaUtil.sdpSessionId
import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil.R0
import io.sip3.salto.ce.util.MediaUtil.computeMos
import io.sip3.salto.ce.util.rtpAddress
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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

    private var cumulativeMetrics = true
    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000
    private var rFactorThreshold: Float = 85F

    private var instances: Int = 1

    private var sdp = mutableMapOf<Long, Pair<SdpSession, SdpSession>>()
    private var rtp = mutableMapOf<Long, RtprSession>()
    private var rtcp = mutableMapOf<Long, RtprSession>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.let { config ->
            config.getBoolean("cumulative-metrics")?.let {
                cumulativeMetrics = it
            }
            config.getLong("trim-to-size-delay")?.let {
                trimToSizeDelay = it
            }
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }

            config.getFloat("r-factor-threshold")?.let {
                rFactorThreshold = it
            }
        }

        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        vertx.setPeriodic(trimToSizeDelay) {
            sdp = MutableMapUtil.mutableMapOf(sdp)
            rtp = MutableMapUtil.mutableMapOf(rtp)
            rtcp = MutableMapUtil.mutableMapOf(rtcp)
        }
        vertx.setPeriodic(expirationDelay) {
            terminateExpiredSessions()
        }

        vertx.eventBus().localConsumer<Pair<SdpSession,SdpSession>>(RoutesCE.sdp + "_info") { event ->
            val sdpSessions = event.body()
            handleSdp(sdpSessions)
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

        GlobalScope.launch(vertx.dispatcher()) {
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

    private fun handleSdp(sdpSessions: Pair<SdpSession, SdpSession>) {
        sdpSessions.toList().forEach { sdpSession ->
            sdp.putIfAbsent(sdpSession.rtpId, sdpSessions)
            sdp.putIfAbsent(sdpSession.rtcpId, sdpSessions)

            // Put same `sdpSession` with Id for RTCP port if different
            if (sdpSession.rtpId != sdpSession.rtcpId) {
                sdp[sdpSession.rtcpId] = sdpSessions
            }
        }
    }

    open fun handleRaw(packet: Packet) {
        val report = RtpReportPayload().apply {
            val payload = Unpooled.wrappedBuffer(packet.payload)
            decode(payload)
        }

        // Ignore cumulative reports from old SIP3 Captain versions
        if (!report.cumulative) {
            route(packet, report)
        }
    }

    open fun route(packet: Packet, report: RtpReportPayload) {
        val index = (packet.srcAddr.port + packet.dstAddr.port).hashCode() % instances
        vertx.eventBus().localSend(RoutesCE.rtpr + "_${index}", Pair(packet, report))
    }

    open fun handle(packet: Packet, report: RtpReportPayload) {
        val sessionId = rtpSessionId(packet.srcAddr.port, packet.dstAddr.port, report.ssrc)
        val session = if (report.source == RtpReportPayload.SOURCE_RTP) {
            rtp.getOrPut(sessionId) { createRtprSession(packet) }
        } else {
            rtcp.getOrPut(sessionId) { createRtprSession(packet) }
        }

        if (report.callId == null) {
            session.sdp?.first?.let { updateWithSdp(report, it) }
        }
        session.add(report)
        sendKeepAlive(session)

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        writeToDatabase("${prefix}_raw", packet, report)

        if (!cumulativeMetrics) {
            calculateMetrics(prefix, packet.srcAddr, packet.dstAddr, report)
        }
    }

    private fun createRtprSession(packet: Packet): RtprSession {
        val session = RtprSession(packet, rFactorThreshold)
        (sdp[sdpSessionId(packet.srcAddr)] ?: sdp[sdpSessionId(packet.dstAddr)])?.let { session.sdp = it }
        return session
    }

    private fun sdpSessionId(address: Address): Long {
        return sdpSessionId(address.addr, address.port)
    }

    private fun updateWithSdp(report: RtpReportPayload, sdpSession: SdpSession) {
        report.callId = sdpSession.callId

        if (report.source == RtpReportPayload.SOURCE_RTCP && report.duration == 0) {
            report.duration = report.expectedPacketCount * sdpSession.ptime
        }

        val codec = if (report.source == RtpReportPayload.SOURCE_RTCP) {
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

    private fun sendKeepAlive(session: RtprSession) {
        session.sdp?.let { (request, response) ->
            val srcAddr = request.rtpAddress()
            val dstAddr = response.rtpAddress()
            vertx.eventBus().localPublish(RoutesCE.media + "_keep-alive", Pair(session.callId, srcAddr.compositeAddrKey(dstAddr)))
        }
    }

    private fun terminateExpiredSessions() {
        val now = System.currentTimeMillis()

        rtp.filterValues { it.terminatedAt + aggregationTimeout < now }
            .forEach { (sessionId, session) ->
                terminateRtprSession(session)
                rtp.remove(sessionId)
            }

        rtcp.filterValues { it.terminatedAt + aggregationTimeout < now }
            .forEach { (sessionId, session) ->
                terminateRtprSession(session)
                rtcp.remove(sessionId)
            }

        sdp.filterValues { it.first.timestamp + aggregationTimeout < now }
            .forEach { (key, _) -> sdp.remove(key) }
    }

    private fun terminateRtprSession(session: RtprSession) {
        session.callId?.let { callId ->
            val index = callId.hashCode() % instances
            vertx.eventBus().localSend(RoutesCE.media + "_$index", session)
        }

        if (cumulativeMetrics) {
            val prefix = when (session.report.source) {
                RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
                RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
                else -> throw IllegalArgumentException("Unsupported RTP Report source: '${session.report.source}'")
            }
            calculateMetrics(prefix, session.srcAddr, session.dstAddr, session.report)
        }
    }

    open fun calculateMetrics(prefix: String, src: Address, dst: Address, report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            src.host?.let { put("src_host", it) }
            dst.host?.let { put("dst_host", it) }
            report.codecName?.let { put("codec", it) }
        }

        report.apply {
            Metrics.summary(prefix + EXPECTED_PACKETS, attributes).record(expectedPacketCount.toDouble())
            Metrics.summary(prefix + LOST_PACKETS, attributes).record(lostPacketCount.toDouble())
            Metrics.summary(prefix + REJECTED_PACKETS, attributes).record(rejectedPacketCount.toDouble())

            if (callId != null && codecName != null) {
                Metrics.summary(prefix + JITTER, attributes).record(avgJitter.toDouble())

                Metrics.summary(prefix + R_FACTOR, attributes).record(rFactor.toDouble())
                Metrics.summary(prefix + MOS, attributes).record(mos.toDouble())

                Metrics.timer(prefix + DURATION, attributes).record(duration.toLong(), TimeUnit.MILLISECONDS)
            } else {
                Metrics.counter(prefix + UNDEFINED, attributes).increment()
            }
        }
    }

    open fun writeToDatabase(prefix: String, packet: Packet, report: RtpReportPayload) {
        val collection = prefix + "_" + timeSuffix.format(packet.timestamp)

        val operation = JsonObject().apply {
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

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}