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

package io.sip3.salto.ce.rtpr

import io.netty.buffer.Unpooled
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.IpUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil.R0
import io.sip3.salto.ce.util.MediaUtil.computeMos
import io.sip3.salto.ce.util.MediaUtil.rtpSessionId
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
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
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var cumulativeMetrics = true
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private val sdp = mutableMapOf<Long, SdpSession>()
    private val rtp = mutableMapOf<Long, RtprSession>()
    private val rtcp = mutableMapOf<Long, RtprSession>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.getBoolean("cumulative-metrics")?.let {
            cumulativeMetrics = it
        }

        // TODO: Why is this `media.expiration-delay`?
        config().getJsonObject("media")?.let { config ->
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
        }

        // TODO: Let's follow the same order as we have in vars and consumers: sdp, rtp, rtcp
        // Periodic task for session expiration
        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()
            // Sessions cleanup
            rtp.filterValues { it.lastReportTimestamp + aggregationTimeout < now }
                    .forEach { (sessionId, session) ->
                        handleCumulative(session.packet, session.report!!)
                        rtp.remove(sessionId)
                    }

            rtcp.filterValues { it.lastReportTimestamp + aggregationTimeout < now }
                    .forEach { (sessionId, session) ->
                        handleCumulative(session.packet, session.report!!)
                        rtcp.remove(sessionId)
                    }

            // SDP sessions cleanup
            // TODO: Maybe we can put it in 1 line? Thinking about Map util method :)
            sdp.filterValues { it.timestamp + aggregationTimeout < now }.forEach { (key, _) -> sdp.remove(key) }
        }

        vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) { event ->
            val sdpInfo = event.body()
            // TODO: sdpInfo.forEach { sdp[it.id] = it }?
            sdpInfo.forEach { sdpSession ->
                sdp[sdpSession.id] = sdpSession
            }
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rtpr_raw) { event ->
            try {
                val packet = event.body()
                handleRaw(packet)
            } catch (e: Exception) {
                // TODO: `handleRaw`?
                logger.error(e) { "RtprHandler 'handle()' failed." }
            }
        }

        vertx.eventBus().localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr) { event ->
            try {
                val (packet, report) = event.body()
                handle(packet, report)
            } catch (e: Exception) {
                logger.error(e) { "RtprHandler 'handle()' failed." }
            }
        }
    }

    // TODO: Isn't it cleaner?
    //    open fun handleRaw(packet: Packet) {
    //        val report = RtpReportPayload().apply {
    //            val payload = Unpooled.wrappedBuffer(packet.payload)
    //            decode(payload)
    //        }
    //        handle(packet, report)
    //    }
    open fun handleRaw(packet: Packet) {
        val payload = Unpooled.wrappedBuffer(packet.payload)
        val report = RtpReportPayload().apply { decode(payload) }
        handle(packet, report)
    }

    open fun handle(packet: Packet, report: RtpReportPayload) {
        // TODO: Prefer prefix to be calculated when it's needed. Now it feels like we need it to `applySdpSession`
        val prefix = "rtpr_" + sourceName(report.source)
        // TODO: Feels like we need another name to this method... Something more meaningfull, like: calculate/updateQosIfPossible
        applySdpSession(packet, report)

        // TODO: I don't like `handleCumulative` and plain code in else method...
        if (report.cumulative) {
            // TODO: From where are we going to get `cumulative` report in general?...
            handleCumulative(packet, report)
        } else {
            val sessionId = rtpSessionId(packet.srcAddr, packet.dstAddr, report.ssrc)

            val session = if (report.source == RtpReportPayload.SOURCE_RTP) {
                rtp.getOrPut(sessionId) { RtprSession(packet) }
            } else {
                rtcp.getOrPut(sessionId) { RtprSession(packet) }
            }

            session.add(report)

            writeToDatabase("${prefix}_raw", packet, report)
        }

        calculateMetrics(prefix, packet, report)
    }

    open fun handleCumulative(packet: Packet, report: RtpReportPayload) {
        val prefix = "rtpr_" + sourceName(report.source)

        writeAttributes(report)
        writeToDatabase("${prefix}_index", packet, report)

        //TODO: send to MediaHandler
    }

    // TODO: I don't really think that we need this method... Doesn't look clean
    open fun sourceName(source: Byte): String {
        return when (source) {
            RtpReportPayload.SOURCE_RTP -> "rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${source}'")
        }
    }

    open fun calculateMetrics(prefix: String, packet: Packet, report: RtpReportPayload) {
        // TODO: Why don't we put this condition explicitly and call `calculateMetrics` per each handle method?
        if (report.cumulative != cumulativeMetrics) {
            return
        }

        val attributes = mutableMapOf<String, Any>().apply {
            packet.srcAddr.host?.let { put("src_host", it) }
            packet.dstAddr.host?.let { put("dst_host", it) }
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

    open fun writeAttributes(report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            put(Attributes.mos, report.mos)
            put(Attributes.r_factor, report.rFactor)
        }

        val prefix = sourceName(report.source)
        vertx.eventBus().localRequest<Any>(RoutesCE.attributes, Pair(prefix, attributes))
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

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    private fun applySdpSession(packet: Packet, report: RtpReportPayload) {
        if (report.callId != null) {
            return
        }

        // Perform calculations only if codec information exists
        (sdp[sessionId(packet.srcAddr)] ?: sdp[sessionId(packet.dstAddr)])?.let { sdpSession ->
            report.callId = sdpSession.callId

            val codec = sdpSession.codec
            report.payloadType = codec.payloadType
            report.codecName = codec.name

            // Raw rFactor value
            val ppl = report.fractionLost * 100
            val ieEff = codec.ie + (95 - codec.ie) * ppl / (ppl + codec.bpl)

            report.rFactor = (R0 - ieEff)

            // MoS
            report.mos = computeMos(report.rFactor)
        }
    }

    // TODO: Can we potentially make it an utility method?
    private fun sessionId(address: Address): Long {
        // TODO: (IpUtil.convertToInt(addr).toLong() shl 32) or (port and 0xfe).toLong()?
        return with(address) {
            val addAsLong = IpUtil.convertToInt(addr).toLong()
            // TODO: I think we can simplify it using and 0xfe
            val port = if (port % 2 == 0) {
                port
            } else {
                port - 1
            }
            ((addAsLong shl 32) or port.toLong())
        }
    }

    inner class RtprSession(val packet: Packet) {

        var reportCount = 0

        var report: RtpReportPayload? = null

        var lastReportTimestamp: Long = Long.MAX_VALUE

        fun add(payload: RtpReportPayload) {
            // TODO: Seems to be too complicated... You already have report as an object of RtprSession...
            if (report == null) {
                report = payload
            } else {
                updateReport(report!!, payload)
            }

            reportCount++
            lastReportTimestamp = payload.startedAt
        }

        private fun updateReport(report: RtpReportPayload, payload: RtpReportPayload) {
            report.apply {
                if (codecName == null) {
                    payload.codecName?.let { codecName = it }
                }

                if (callId == null) {
                    payload.callId?.let { callId = it }
                }

                expectedPacketCount += payload.expectedPacketCount
                receivedPacketCount += payload.receivedPacketCount
                rejectedPacketCount += payload.rejectedPacketCount
                lostPacketCount += payload.lostPacketCount

                duration += payload.duration
                fractionLost = lostPacketCount.toFloat() / expectedPacketCount

                lastJitter = payload.lastJitter
                avgJitter = (avgJitter * reportCount + payload.avgJitter) / (reportCount + 1)
                if (maxJitter < lastJitter) {
                    maxJitter = lastJitter
                }
                if (minJitter > lastJitter) {
                    minJitter = lastJitter
                }

                if (payload.rFactor > 0.0F)
                    if (rFactor > 0.0F) {
                        rFactor = (rFactor * reportCount + payload.rFactor) / (reportCount + 1)
                    } else {
                        rFactor = payload.rFactor
                    }

                // MoS
                mos = computeMos(rFactor)
            }
        }
    }
}