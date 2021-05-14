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

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.rtpr.RtprSession
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
 * Handles media sessions
 */
@Instance
open class MediaHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "media"

        const val REPORTS = PREFIX + "_reports"
        const val BAD_REPORTS = PREFIX + "_bad-reports"
        const val BAD_REPORTS_FRACTION = PREFIX + "_bad-reports-fraction"

        const val DURATION = PREFIX + "_duration"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 4000L
    private var aggregationTimeout: Long = 30000L

    private var instances: Int = 1

    private var media = mutableMapOf<String, MutableMap<String, MediaSession>>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.let { config ->
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
            media = MutableMapUtil.mutableMapOf(media)
        }
        vertx.setPeriodic(expirationDelay) {
            terminateExpiredMediaSessions()
        }

        vertx.eventBus().localConsumer<MediaControl>(RoutesCE.media + "_control") { event ->
            try {
                val mediaControl = event.body()
                handleMediaControl(mediaControl)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleMediaControl()' failed." }
            }
        }

        vertx.eventBus().localConsumer<RtprSession>(RoutesCE.media + "_keep-alive") { event ->
            try {
                val rtprSession = event.body()
                handleKeepAlive(rtprSession)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleKeepAlive()' failed." }
            }
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val index = vertx.sharedData().getLocalCounter(RoutesCE.media).await()
            vertx.eventBus().localConsumer<RtprSession>(RoutesCE.media + "_${index.andIncrement.await()}") { event ->
                try {
                    val session = event.body()
                    handle(session)
                } catch (e: Exception) {
                    logger.error(e) { "MediaHandler 'handle()' failed." }
                }
            }
        }
    }

    open fun handleMediaControl(mediaControl: MediaControl) {
        val sdpSession = mediaControl.sdpSession
        val srcAddr = sdpSession.src.rtpAddress()
        val dstAddr = sdpSession.dst.rtpAddress()

        media.getOrPut(mediaControl.callId) { mutableMapOf() }
            .putIfAbsent(dstAddr.compositeAddrKey(srcAddr), MediaSession(srcAddr, dstAddr, mediaControl))
    }

    open fun handleKeepAlive(rtprSession: RtprSession) {
        rtprSession.mediaControl?.sdpSession?.let { sdpSession ->
            val srcAddr = sdpSession.src.rtpAddress()
            val dstAddr = sdpSession.dst.rtpAddress()
            val legId = srcAddr.compositeAddrKey(dstAddr)

            media.get(rtprSession.callId)?.get(legId)?.let { mediaSession ->
                mediaSession.updatedAt = System.currentTimeMillis()

                if (mediaSession.mediaControl.recording == null
                    && rtprSession.report.source == RtpReportPayload.SOURCE_RTP) {

                    vertx.eventBus().localSend(RoutesCE.media + "_update", Pair(rtprSession, mediaSession.mediaControl))
                }
            }
        }
    }

    open fun handle(rtprSession: RtprSession) {
        rtprSession.mediaControl?.sdpSession?.let { sdpSession ->
            val srcAddr = sdpSession.src.rtpAddress()
            val dstAddr = sdpSession.dst.rtpAddress()
            val legId = srcAddr.compositeAddrKey(dstAddr)

            val callId = rtprSession.callId!!
            val mediaSession = media.getOrPut(callId) { mutableMapOf() }
                .getOrPut(legId) {
                    logger.warn { "Media Session not found. Call ID: $callId, Leg ID: $legId, RtprSession source: ${rtprSession.report.source}" }
                    MediaSession(srcAddr, dstAddr, rtprSession.mediaControl!!)
                }
            mediaSession.add(rtprSession)
        }
    }

    open fun terminateExpiredMediaSessions() {
        val now = System.currentTimeMillis()

        media.filterValues { sessions ->
            sessions.filterValues { session ->
                session.updatedAt + aggregationTimeout < now
            }.forEach { (sid, session) ->
                sessions.remove(sid)
                terminateMediaSession(session)
            }
            return@filterValues sessions.isEmpty()
        }.forEach { (callId, _) ->
            media.remove(callId)
        }
    }

    open fun terminateMediaSession(session: MediaSession) {
        if (session.hasMedia()) {
            writeAttributes(session)
            writeToDatabase("rtpr_${PREFIX}_index", session)
            calculateMetrics(session)
        }
    }

    open fun calculateMetrics(session: MediaSession) {
        session.apply {
            val attributes = mutableMapOf<String, Any>().apply {
                srcAddr.host?.let { put("src_host", it) }
                dstAddr.host?.let { put("dst_host", it) }
                codecNames.firstOrNull()?.let { put("codec", it) }
            }

            Metrics.summary(REPORTS, attributes).record(reportCount.toDouble())
            Metrics.summary(BAD_REPORTS, attributes).record(badReportCount.toDouble())
            Metrics.summary(BAD_REPORTS_FRACTION, attributes).record(badReportFraction)

            Metrics.timer(DURATION, attributes).record(duration, TimeUnit.MILLISECONDS)
        }
    }

    open fun writeAttributes(session: MediaSession) {
        val attributes = mutableMapOf<String, Any>().apply {
            put(Attributes.mos, session.mos)
            put(Attributes.r_factor, session.rFactor)
            put(Attributes.one_way, session.isOneWay)
            put(Attributes.undefined_codec, session.hasUndefinedCodec)
            put(Attributes.missed_peer, session.missedPeer)

            put(Attributes.bad_report_fraction, session.badReportFraction)
        }

        vertx.eventBus().localSend(RoutesCE.attributes, Pair(PREFIX, attributes))
    }

    open fun writeToDatabase(prefix: String, session: MediaSession) {
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
                put("codec_names", session.codecNames.toList())
                put("duration", session.duration)

                put("report_count", session.reportCount)
                put("bad_report_count", session.badReportCount)
                put("bad_report_fraction", session.badReportFraction)

                put("one_way", session.isOneWay)
                put("undefined_codec", session.hasUndefinedCodec)
                put("missed_peer", session.missedPeer)

                put("mos", session.mos)
                put("r_factor", session.rFactor)

                session.forward.rtp?.let { put("forward_rtp", it.toJson()) }
                session.forward.rtcp?.let { put("forward_rtcp", it.toJson()) }
                session.reverse.rtp?.let { put("reverse_rtp", it.toJson()) }
                session.reverse.rtcp?.let { put("reverse_rtcp", it.toJson()) }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    private fun RtprSession.toJson(): JsonObject {
        return JsonObject().apply {
            put("created_at", createdAt)
            put("terminated_at", createdAt + report.duration)

            put("src_addr", srcAddr.addr)
            put("src_port", srcAddr.port)
            srcAddr.host?.let { put("src_host", it) }

            put("dst_addr", dstAddr.addr)
            put("dst_port", dstAddr.port)
            dstAddr.host?.let { put("dst_host", it) }

            put("call_id", report.callId)
            put("payload_type", report.payloadType.toInt())
            put("codec_name", report.codecName)
            put("ssrc", report.ssrc)
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

            put("missed_peer", missedPeer)
        }
    }
}