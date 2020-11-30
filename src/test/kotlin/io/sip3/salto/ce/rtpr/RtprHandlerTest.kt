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

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class RtprHandlerTest : VertxTest() {

    companion object {

        val SRC_ADDR = Address().apply {
            addr = "10.10.10.10"
            port = 10500
        }

        val DST_ADDR = Address().apply {
            addr = "10.20.20.20"
            port = 20500
        }

        // Periodic RTP report with RTP source
        val RTPR_1 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            cumulative = false
            payloadType = 1
            ssrc = 2
            callId = "some@call.id"
            codecName = "PCMA"

            expectedPacketCount = 3
            receivedPacketCount = 4
            lostPacketCount = 5
            rejectedPacketCount = 6

            duration = 7

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            rFactor = 12F
            mos = 13F
            fractionLost = 14F

            createdAt = 1579511172674
            startedAt = 1579522272674
        }

        // Periodic RTP report without Call-ID
        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            cumulative = false
            payloadType = 0
            ssrc = 2

            expectedPacketCount = 3
            receivedPacketCount = 4
            lostPacketCount = 5
            rejectedPacketCount = 6

            duration = 7

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            createdAt = 1579511172674
            startedAt = 1579522272674
        }


        // Packet with periodic RTP report with RTP source
        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_1.encode().array()
        }

        // Packet with periodic RTP report without Call-ID
        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_2.encode().array()
        }

        // Packet with cumulative RTP report with RTCP source
        val PACKET_3 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RtpReportPayload().apply {
                source = RtpReportPayload.SOURCE_RTCP
                cumulative = true
                payloadType = 1
            }.encode().array()
        }

        // SDP
        val SDP_SESSION = SdpSession().apply {
            callId = "callId_uuid@domain.io"
            timestamp = System.currentTimeMillis()

            address = SRC_ADDR.addr
            rtpPort = SRC_ADDR.port
            rtcpPort = SRC_ADDR.port + 1

            codecs = mutableListOf(Codec().apply {
                name = "PCMU"
                payloadTypes = listOf(0)
                clockRate = 8000
                bpl = 4.3F
                ie = 0F
            })
        }
    }

    @Test
    fun `Write periodic RTP report to database`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class)
                },
                execute = {
                    vertx.eventBus().localRequest<Any>(RoutesCE.rtpr, PACKET_1)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, operation) = event.body()

                        val document = operation.getJsonObject("document")

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_raw"))

                            assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                            assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                            assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                            assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                            assertEquals(RTPR_1.createdAt, document.getLong("created_at"))
                            assertEquals(RTPR_1.startedAt, document.getLong("started_at"))

                            assertEquals(RTPR_1.callId, document.getString("call_id"))
                            assertEquals(RTPR_1.codecName, document.getString("codec_name"))

                            assertEquals(RTPR_1.payloadType, document.getInteger("payload_type").toByte())
                            assertEquals(RTPR_1.ssrc, document.getLong("ssrc"))


                            val packets = document.getJsonObject("packets")
                            assertEquals(RTPR_1.expectedPacketCount, packets.getInteger("expected"))
                            assertEquals(RTPR_1.receivedPacketCount, packets.getInteger("received"))
                            assertEquals(RTPR_1.lostPacketCount, packets.getInteger("lost"))
                            assertEquals(RTPR_1.rejectedPacketCount, packets.getInteger("rejected"))

                            val jitter = document.getJsonObject("jitter")
                            assertEquals(RTPR_1.lastJitter, jitter.getDouble("last").toFloat())
                            assertEquals(RTPR_1.avgJitter, jitter.getDouble("avg").toFloat())
                            assertEquals(RTPR_1.minJitter, jitter.getDouble("min").toFloat())
                            assertEquals(RTPR_1.maxJitter, jitter.getDouble("max").toFloat())

                            assertEquals(RTPR_1.rFactor, document.getFloat("r_factor"))
                            assertEquals(RTPR_1.mos, document.getFloat("mos"))
                            assertEquals(RTPR_1.fractionLost, document.getFloat("fraction_lost"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Handle periodic RTP Report and update with SDP Info`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                        put("media", JsonObject().apply {
                            put("rtp-r", JsonObject().apply {
                                put("expiration-delay", 100L)
                                put("aggregation-timeout", 500L)
                            })
                        })
                    })
                },
                execute = {
                    vertx.eventBus().localPublish(RoutesCE.sdp + "_info", listOf(SDP_SESSION))
                    vertx.eventBus().localRequest<Any>(RoutesCE.rtpr + "_rtcp", Pair(PACKET_2, RTPR_2))
                },
                assert = {
                    vertx.eventBus().consumer<RtprSession>(RoutesCE.media) { event ->
                        val session = event.body()

                        context.verify {
                            with(session) {
                                assertEquals(1, reportCount)
                                assertEquals(PACKET_2.timestamp, session.timestamp)
                                assertEquals(DST_ADDR, dstAddr)
                                assertEquals(SRC_ADDR, srcAddr)

                                with(session.report) {
                                    assertEquals(SDP_SESSION.callId, callId)
                                    assertEquals(4.4092855F, mos)
                                    assertEquals(93.2F, rFactor)
                                }
                            }
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Handle RTP Report and generate RtprSession`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class)
                },
                execute = {
                    vertx.eventBus().localRequest<Any>(RoutesCE.rtpr + "_rtcp", Pair(PACKET_1, RTPR_1))
                },
                assert = {
                    vertx.eventBus().consumer<RtprSession>(RoutesCE.media) { event ->
                        val session = event.body()

                        context.verify {
                            with(session) {
                                assertEquals(1, reportCount)
                                assertEquals(PACKET_1.timestamp, session.timestamp)
                                assertEquals(DST_ADDR, dstAddr)
                                assertEquals(SRC_ADDR, srcAddr)
                                assertEquals(RTPR_1, session.report)

                            }
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Calculate metrics for periodic RTP reports`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                        put("media", JsonObject().apply {
                            put("rtp-r", JsonObject().apply {
                                put("cumulative-metrics", false)
                            })
                        })
                    })
                },
                execute = {
                    vertx.eventBus().localRequest<Any>(RoutesCE.rtpr, PACKET_1)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, _) = event.body()

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_raw"))
                        }
                    }

                    vertx.setPeriodic(200L) {
                        registry.find("rtpr_rtp_r-factor").summaries()
                                .firstOrNull { it.mean().toFloat() == RTPR_1.rFactor }
                                ?.let { summary ->
                                    context.verify {
                                        val tags = summary.id.tags
                                        assertTrue(tags.isNotEmpty())
                                        assertTrue(tags.any { it.value == RTPR_1.codecName })
                                    }
                                    context.completeNow()
                                }
                    }
                }
        )
    }

    @Test
    fun `Calculate metrics for cumulative RTP Report`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                        put("media", JsonObject().apply {
                            put("rtp-r", JsonObject().apply {
                                put("expiration-delay", 100L)
                                put("aggregation-timeout", 200L)
                            })
                        })
                    })
                },
                execute = {
                    vertx.eventBus().localPublish(RoutesCE.sdp + "_info", listOf(SDP_SESSION))
                    vertx.eventBus().localRequest<Any>(RoutesCE.rtpr, PACKET_2)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, _) = event.body()

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_raw"))
                        }
                    }

                    vertx.setPeriodic(200L) {
                        registry.find("rtpr_rtp_r-factor").summaries()
                                .firstOrNull { it.mean().toFloat() == 93.2F }
                                ?.let { summary ->
                                    context.verify {
                                        val tags = summary.id.tags
                                        assertTrue(tags.isNotEmpty())
                                        assertTrue(tags.any { it.value == SDP_SESSION.codecs.first().name })
                                    }
                                    context.completeNow()
                                }
                        }
                }
        )
    }
}
