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

package io.sip3.salto.ce.recording

import io.netty.buffer.Unpooled
import io.sip3.commons.PacketTypes
import io.sip3.commons.domain.payload.RecordingPayload
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Handles Recording Payload
 */
@Instance
open class RecordingHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var instances = 1
    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun start() {
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rec) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("RecordingHandler 'handle()' failed.", e)
            }
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val index = vertx.sharedData().getLocalCounter(RoutesCE.rec).await()
            vertx.eventBus()
                .localConsumer<Pair<Packet, RecordingPayload>>(RoutesCE.rec + "_${index.andIncrement.await()}") { event ->
                    try {
                        val (packet, recording) = event.body()
                        handleRecording(packet, recording)
                    } catch (e: Exception) {
                        logger.error(e) { "RecordingHandler 'handleRecording()' failed." }
                    }
                }
        }
    }

    open fun handle(packet: Packet) {
        val recording = RecordingPayload().apply {
            val payload = Unpooled.wrappedBuffer(packet.payload)
            decode(payload)
        }

        val index = abs(recording.callId.hashCode()) % instances
        vertx.eventBus().localSend(RoutesCE.rec + "_${index}", Pair(packet, recording))
    }

    open fun handleRecording(packet: Packet, recording: RecordingPayload) {
        writeToDatabase(packet, recording)

        if (recording.type == RecordingPayload.TYPE_RTCP) {
            val rtcpPacket = Packet().apply {
                timestamp = packet.timestamp
                srcAddr = packet.srcAddr
                dstAddr = packet.dstAddr
                protocolCode = PacketTypes.RTCP
                source = "sip3"
                payload = recording.payload
            }
            vertx.eventBus().localSend(RoutesCE.rtcp, rtcpPacket)
        }
    }

    open fun writeToDatabase(packet: Packet, recording: RecordingPayload) {
        val collection = "rec_raw_" + timeSuffix.format(packet.timestamp)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                val timestamp = packet.timestamp
                put("created_at", timestamp.time)
                put("nanos", timestamp.nanos)

                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("call_id", recording.callId)
                put("type", recording.type.toInt())
                put("raw_data", recording.payload)
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}
