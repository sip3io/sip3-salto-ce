/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.collections.PeriodicallyExpiringHashMap
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
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

@Instance
open class RtprBulkWriter : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000
    private var bulkPacketLimit = 1024

    private var instances: Int = 1

    private lateinit var rtp: PeriodicallyExpiringHashMap<String, RtprBulk>
    private lateinit var rtcp: PeriodicallyExpiringHashMap<String, RtprBulk>

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
            config.getInteger("bulk-packet-limit")?.let {
                bulkPacketLimit = it
            }
        }
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        rtp = PeriodicallyExpiringHashMap.Builder<String, RtprBulk>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, bulk -> bulk.updatedAt + aggregationTimeout }
            .onRemain { _, bulk -> onRemain("rtp", bulk) }
            .onExpire { _, bulk -> onExpire("rtp", bulk) }
            .build(vertx)

        rtcp = PeriodicallyExpiringHashMap.Builder<String, RtprBulk>()
            .delay(expirationDelay)
            .period((aggregationTimeout / expirationDelay).toInt())
            .expireAt { _, bulk -> bulk.updatedAt + aggregationTimeout }
            .onRemain { _, bulk -> onRemain("rtcp", bulk) }
            .onExpire { _, bulk -> onExpire("rtcp", bulk) }
            .build(vertx)

        vertx.eventBus().localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_bulk_writer") { event ->
            try {
                val (packet, report) = event.body()
                route(packet, report)
            } catch (e: Exception) {
                logger.error(e) { "RtprBulkWriter 'route()' failed." }
            }
        }

        GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
            val index = vertx.sharedData().getLocalCounter(RoutesCE.rtpr + "_bulk_writer").await()
            vertx.eventBus()
                .localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_bulk_writer_${index.andIncrement.await()}") { event ->
                    try {
                        val (packet, report) = event.body()
                        handle(packet, report)
                    } catch (e: Exception) {
                        logger.error(e) { "RtprBulkWriter 'handle()' failed." }
                    }
                }
        }
    }

    private fun onRemain(source: String, bulk: RtprBulk) {
        if (bulk.expectedPackets >= bulkPacketLimit) {
            writeToDatabase("rtpr_${source}_raw", bulk)
            bulk.clear()
        }
    }

    private fun onExpire(source: String, bulk: RtprBulk) {
        if (bulk.expectedPackets > 0) {
            writeToDatabase("rtpr_${source}_raw", bulk)
        }
    }

    open fun route(packet: Packet, report: RtpReportPayload) {
        val index = (packet.srcAddr.port + packet.dstAddr.port).hashCode() % instances
        vertx.eventBus().localSend(RoutesCE.rtpr + "_bulk_writer_${index}", Pair(packet, report))
    }

    private fun handle(packet: Packet, report: RtpReportPayload) {
        val bulkId = "${packet.srcAddr.port}:${packet.dstAddr.port}:${report.callId}"
        val bulks = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> rtp
            RtpReportPayload.SOURCE_RTCP -> rtcp
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }

        val rtprBulk = bulks.getOrPut(bulkId) { RtprBulk(packet.srcAddr, packet.dstAddr) }
        rtprBulk.add(report)
    }

    open fun writeToDatabase(prefix: String, bulk: RtprBulk) {
        val firstReport = bulk.reports.first()
        val collection = prefix + "_" + timeSuffix.format(firstReport.createdAt)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("reported_at", firstReport.reportedAt)
                put("created_at", firstReport.createdAt)
                firstReport.callId?.let { put("call_id", it) }

                val src = bulk.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = bulk.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                bulk.reports.map { report ->
                    JsonObject().apply {
                        put("reported_at", report.reportedAt)
                        put("created_at", report.createdAt)

                        put("payload_type", report.payloadType.toInt())
                        put("ssrc", report.ssrc)
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
                }.also { put("reports", it) }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }


    inner class RtprBulk(val srcAddr: Address, val dstAddr: Address) {

        var updatedAt = System.currentTimeMillis()
        val reports = mutableListOf<RtpReportPayload>()

        var expectedPackets = 0

        fun add(report: RtpReportPayload) {
            reports.add(report)
            expectedPackets += report.expectedPacketCount
            updatedAt = System.currentTimeMillis()
        }

        fun clear() {
            reports.clear()
            expectedPackets = 0
            updatedAt = System.currentTimeMillis()
        }
    }
}