/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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
import io.sip3.commons.domain.media.Codec
import io.sip3.commons.domain.media.MediaAddress
import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.getBytes
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RtprHandlerTest : VertxTest() {

    companion object {

        val NOW = System.currentTimeMillis()

        val SRC_ADDR = Address().apply {
            addr = "10.10.10.10"
            port = 10500
        }

        val SRC_ADDR_RTCP = Address().apply {
            addr = SRC_ADDR.addr
            port = SRC_ADDR.port + 1
        }

        val DST_ADDR = Address().apply {
            addr = "10.20.20.20"
            port = 20500
        }

        val DST_ADDR_RTCP = Address().apply {
            addr = DST_ADDR.addr
            port = DST_ADDR.port + 1
        }

        // Periodic RTP report with RTP source
        val RTPR_1 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            recorded = true
            payloadType = 1
            ssrc = 2
            callId = "callId_uuid@domain.io"
            codecName = "PCMA"

            expectedPacketCount = 100
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
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = NOW
            createdAt = NOW - 10000L
        }

        // Periodic RTP report without Call-ID
        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTCP
            recorded = false
            payloadType = 0
            ssrc = 2

            expectedPacketCount = 100
            receivedPacketCount = 4
            lostPacketCount = 5
            rejectedPacketCount = 6

            duration = 7

            lastJitter = 8F
            avgJitter = 9F
            minJitter = 10F
            maxJitter = 11F

            rFactor = 45F
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = NOW
            createdAt = NOW - 10000L
        }


        // Packet with periodic RTP report with RTP source
        val PACKET_1 = Packet().apply {
            createdAt = NOW
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_1.encode().getBytes()
            attributes = mutableMapOf("custom_attr" to "value")
        }

        // Packet with periodic RTP report without Call-ID
        val PACKET_2 = Packet().apply {
            createdAt = NOW
            srcAddr = DST_ADDR_RTCP
            dstAddr = SRC_ADDR_RTCP
            payload = RTPR_2.encode().getBytes()
            attributes = mutableMapOf("custom_attr" to "value2")
        }

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = NOW

            callId = "callId_uuid@domain.io"

            sdpSession = SdpSession().apply {
                src = MediaAddress().apply {
                    addr = SRC_ADDR.addr
                    rtpPort = SRC_ADDR.port
                    rtcpPort = SRC_ADDR_RTCP.port
                }
                dst = MediaAddress().apply {
                    addr = DST_ADDR.addr
                    rtpPort = DST_ADDR.port
                    rtcpPort = DST_ADDR_RTCP.port
                }

                codecs = mutableListOf(Codec().apply {
                    name = "PCMU"
                    payloadTypes = listOf(0)
                    clockRate = 8000
                    bpl = 4.3F
                    ie = 0F
                })
            }
        }
    }

    @Test
    fun `Handle periodic RTP Report and update with SDP Info`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 500L)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(PACKET_2, RTPR_2))
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.rtpr + "_session") { event ->
                    val session = event.body()

                    context.verify {
                        session.apply {
                            assertEquals(1, reportCount)
                            assertEquals(1, badReportCount)
                            assertEquals(MEDIA_CONTROL.callId, callId)

                            assertEquals(1, codecs.size)
                            assertEquals(MEDIA_CONTROL.sdpSession.codecs.first().name, codecs.first())

                            assertEquals(RTPR_2.createdAt, session.createdAt)
                            assertEquals(DST_ADDR_RTCP, dstAddr)
                            assertEquals(SRC_ADDR_RTCP, srcAddr)

                            assertFalse(recorded)
                            forward!!.report.apply {
                                assertEquals(MediaUtil.computeMos(rFactor), mos)
                                assertEquals(42.12473F, rFactor)
                            }
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle periodic RTP Report and send session to rtpr_update`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class)
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_1)
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.rtpr + "_update") { event ->
                    val session = event.body()

                    context.verify {
                        session.apply {
                            assertEquals(1, reportCount)
                            assertEquals(RTPR_1.createdAt, session.createdAt)
                            assertEquals(DST_ADDR, dstAddr)
                            assertEquals(SRC_ADDR, srcAddr)
                            assertNotNull(session.forward)
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Handle RTP Report from RTCP and generate RtprStream`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 500L)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(PACKET_1, RTPR_1))
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.rtpr + "_session") { event ->
                    val session = event.body()

                    context.verify {
                        session.apply {
                            assertEquals(1, reportCount)
                            assertEquals(RTPR_1.createdAt, session.createdAt)
                            assertEquals(DST_ADDR, dstAddr)
                            assertEquals(SRC_ADDR, srcAddr)
                            assertReport(RTPR_1, session.forward!!.report)

                            assertEquals(PACKET_1.attributes!!.get("custom_attr"), session.attributes.get("custom_attr"))
                        }
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Calculate metrics for RTP Report`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 200L)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_2)
            },
            assert = {
                vertx.setPeriodic(200L) {
                    registry.find("rtpr_rtcp_r-factor").summaries()
                        .firstOrNull { it.mean().toFloat() == 42.12473F }
                        ?.let { summary ->
                            context.verify {
                                val tags = summary.id.tags
                                assertTrue(tags.isNotEmpty())
                                assertTrue(tags.any { it.value == MEDIA_CONTROL.sdpSession.codecs.first().name })
                                assertTrue(tags.any { it.key == Attributes.ranked })
                                assertTrue(tags.any { it.key == "custom_attr" })
                            }
                            context.completeNow()
                        }
                }
            }
        )
    }


    @Test
    fun `Validate min expected packets for QoS metrics`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 200L)
                            put("min_expected_packets", 101)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_2)
            },
            assert = {
                vertx.setPeriodic(200L) {
                    registry.find("rtpr_rtcp_r-factor").summaries()
                        .firstOrNull { it.mean().toFloat() == 42.12473F }
                        ?.let { summary ->
                            context.verify {
                                val tags = summary.id.tags
                                assertTrue(tags.isNotEmpty())
                                assertTrue(tags.any { it.value == MEDIA_CONTROL.sdpSession.codecs.first().name })
                                assertTrue(tags.none { it.key == Attributes.ranked })
                            }
                            context.completeNow()
                        }
                }
            }
        )
    }

    private fun assertReport(origin: RtpReportPayload, report: RtpReportPayload) {
        report.apply {
            assertEquals(origin.source, source)

            assertEquals(origin.reportedAt, reportedAt)
            assertEquals(origin.createdAt, createdAt)
            assertEquals(origin.duration, duration)

            assertEquals(origin.codecName, codecName)
            assertEquals(origin.callId, callId)
            assertEquals(origin.ssrc, ssrc)

            assertEquals(origin.expectedPacketCount, expectedPacketCount)
            assertEquals(origin.lostPacketCount, lostPacketCount)
            assertEquals(origin.receivedPacketCount, receivedPacketCount)
            assertEquals(origin.rejectedPacketCount, rejectedPacketCount)
            assertEquals(origin.markerPacketCount, markerPacketCount)
            assertEquals(origin.fractionLost, fractionLost)

            assertEquals(origin.minJitter, minJitter)
            assertEquals(origin.maxJitter, maxJitter)
            assertEquals(origin.avgJitter, avgJitter)
            assertEquals(origin.lastJitter, lastJitter)
            assertEquals(origin.rFactor, rFactor)
            assertEquals(MediaUtil.computeMos(origin.rFactor), mos)
        }
    }
}
