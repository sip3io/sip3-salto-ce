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

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.commons.domain.media.*
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Timestamp

@ExtendWith(MockKExtension::class)
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
            fractionLost = 14F

            createdAt = 1579511172674
            startedAt = 1579522272674
        }

        // Periodic RTP report without Call-ID
        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTCP
            cumulative = false
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

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "callId_uuid@domain.io"

            sdpSession = SdpSession().apply {
                src = MediaAddress().apply {
                    addr = SRC_ADDR.addr
                    rtpPort = SRC_ADDR.port
                    rtcpPort = SRC_ADDR.port + 1
                }
                dst = MediaAddress().apply {
                    addr = DST_ADDR.addr
                    rtpPort = DST_ADDR.port
                    rtcpPort = DST_ADDR.port + 1
                }

                codecs = mutableListOf(Codec().apply {
                    name = "PCMU"
                    payloadTypes = listOf(0)
                    clockRate = 8000
                    bpl = 4.3F
                    ie = 0F
                })
            }

            recording = Recording()
        }
    }

    @BeforeEach
    fun `Mock all`() {
        mockkConstructor(AttributesRegistry::class)
        every {
            anyConstructed<AttributesRegistry>().handle(any(), any())
        } just Runs
    }

    @Test
    fun `Write periodic RTP report from RTP to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_1)
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

                        document.getJsonObject("packets").apply {
                            assertEquals(RTPR_1.expectedPacketCount, getInteger("expected"))
                            assertEquals(RTPR_1.receivedPacketCount, getInteger("received"))
                            assertEquals(RTPR_1.lostPacketCount, getInteger("lost"))
                            assertEquals(RTPR_1.rejectedPacketCount, getInteger("rejected"))
                        }

                        document.getJsonObject("jitter").apply {
                            assertEquals(RTPR_1.lastJitter, getDouble("last").toFloat())
                            assertEquals(RTPR_1.avgJitter, getDouble("avg").toFloat())
                            assertEquals(RTPR_1.minJitter, getDouble("min").toFloat())
                            assertEquals(RTPR_1.maxJitter, getDouble("max").toFloat())
                        }

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
    fun `Write periodic RTP report from RTCP to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_rtcp_raw"))

                        assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                        assertEquals(RTPR_2.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_2.startedAt, document.getLong("started_at"))

                        assertEquals(RTPR_2.callId, document.getString("call_id"))
                        assertEquals(RTPR_2.codecName, document.getString("codec_name"))

                        assertEquals(RTPR_2.payloadType, document.getInteger("payload_type").toByte())
                        assertEquals(RTPR_2.ssrc, document.getLong("ssrc"))

                        document.getJsonObject("packets").apply {
                            assertEquals(RTPR_2.expectedPacketCount, getInteger("expected"))
                            assertEquals(RTPR_2.receivedPacketCount, getInteger("received"))
                            assertEquals(RTPR_2.lostPacketCount, getInteger("lost"))
                            assertEquals(RTPR_2.rejectedPacketCount, getInteger("rejected"))
                        }

                        document.getJsonObject("jitter").apply {
                            assertEquals(RTPR_2.lastJitter, getDouble("last").toFloat())
                            assertEquals(RTPR_2.avgJitter, getDouble("avg").toFloat())
                            assertEquals(RTPR_2.minJitter, getDouble("min").toFloat())
                            assertEquals(RTPR_2.maxJitter, getDouble("max").toFloat())
                        }

                        assertEquals(RTPR_2.rFactor, document.getFloat("r_factor"))
                        assertEquals(RTPR_2.mos, document.getFloat("mos"))
                        assertEquals(RTPR_2.fractionLost, document.getFloat("fraction_lost"))
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
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(PACKET_2, RTPR_2))
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.media + "_0") { event ->
                    val session = event.body()

                    context.verify {
                        session.apply {
                            assertEquals(1, reportCount)
                            assertEquals(0, badReportCount)
                            assertEquals(MEDIA_CONTROL.callId, callId)

                            assertEquals(1, codecNames.size)
                            assertEquals(MEDIA_CONTROL.sdpSession.codecs.first().name, codecNames.first())

                            assertEquals(RTPR_1.startedAt, session.createdAt)
                            assertEquals(DST_ADDR, dstAddr)
                            assertEquals(SRC_ADDR, srcAddr)

                            report.apply {
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
    fun `Handle RTP Report from RTCP and generate RtprSession`() {
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
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(PACKET_1, RTPR_1))
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.media + "_0") { event ->
                    val session = event.body()

                    context.verify {
                        session.apply {
                            assertEquals(1, reportCount)
                            assertEquals(RTPR_1.startedAt, session.createdAt)
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
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_1)
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
                                assertTrue(tags.any { it.key == Attributes.ranked && it.value == "true" })
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
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, _) = event.body()

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_rtcp_raw"))
                    }
                }

                vertx.setPeriodic(200L) {
                    registry.find("rtpr_rtcp_r-factor").summaries()
                        .firstOrNull { it.mean().toFloat() == 93.2F }
                        ?.let { summary ->
                            context.verify {
                                val tags = summary.id.tags
                                assertTrue(tags.isNotEmpty())
                                assertTrue(tags.any { it.value == MEDIA_CONTROL.sdpSession.codecs.first().name })
                                assertTrue(tags.any { it.key == Attributes.ranked })
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
                        put("rtp-r", JsonObject().apply {
                            put("expiration-delay", 100L)
                            put("aggregation-timeout", 200L)
                            put("min-expected-packets", 101)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr, PACKET_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, _) = event.body()

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_rtcp_raw"))
                    }
                }

                vertx.setPeriodic(200L) {
                    registry.find("rtpr_rtcp_r-factor").summaries()
                        .firstOrNull { it.mean().toFloat() == 93.2F }
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

    @Test
    fun `Send keep-alive to Media Handler`() {
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
                vertx.eventBus().localPublish(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.rtpr + "_rtcp", Pair(PACKET_1, RTPR_1))
            },
            assert = {
                vertx.eventBus().consumer<RtprSession>(RoutesCE.media + "_keep-alive") { event ->
                    val session = event.body()

                    context.verify {
                        assertFalse(event.isSend)
                        assertEquals(RTPR_1.callId, session.callId)
                        assertEquals(RTPR_1.startedAt, session.createdAt)
                    }

                    context.completeNow()
                }
            }
        )
    }

    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}
