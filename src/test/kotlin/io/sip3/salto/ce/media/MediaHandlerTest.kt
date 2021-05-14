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

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.sip3.commons.domain.media.*
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.rtpr.RtprSession
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class MediaHandlerTest : VertxTest() {

    companion object {

        val TEST_CONFIG = JsonObject().apply {
            put("media", JsonObject().apply {
                put("expiration-delay", 100L)
                put("aggregation-timeout", 500L)
            })
        }

        val SRC_ADDR = Address().apply {
            addr = "10.10.10.10"
            port = 10500
            host = "SomeHost"
        }

        val DST_ADDR = Address().apply {
            addr = "10.20.20.20"
            port = 20500
        }

        val RTPR_1 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 2

            callId = "callId_uuid@domain.io"

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

        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_1.encode().array()
        }

        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            codecName = "PCMU"
            payloadType = 0
            ssrc = 3

            callId = "callId_uuid@domain.io"

            expectedPacketCount = 4
            receivedPacketCount = 5
            lostPacketCount = 6
            rejectedPacketCount = 7

            duration = 8

            lastJitter = 9F
            avgJitter = 10F
            minJitter = 11F
            maxJitter = 12F

            rFactor = 13F
            mos = 14F
            fractionLost = 15F

            createdAt = 1579511272674
            startedAt = 1579522372674
        }

        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = DST_ADDR
            dstAddr = SRC_ADDR
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

        val RTPR_SESSION_1 = RtprSession(PACKET_1).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_1)
            missedPeer = true
        }

        val RTPR_SESSION_2 = RtprSession(PACKET_2).apply {
            mediaControl = MEDIA_CONTROL
            add(RTPR_2)
        }
    }

    @Test
    fun `Write MediaSession to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaHandler::class, TEST_CONFIG)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_1)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_media_index"))

                        assertAddresses(PACKET_1, document)

                        assertEquals(RTPR_SESSION_1.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_SESSION_2.terminatedAt, document.getLong("terminated_at"))

                        assertEquals(2, document.getInteger("report_count"))
                        assertEquals(0, document.getInteger("bad_report_count"))
                        assertEquals(0, document.getInteger("bad_report_fraction"))

                        assertEquals(12.0, document.getDouble("r_factor"))
                        assertEquals(13.0, document.getDouble("mos"))


                        assertEquals(false, document.getBoolean("one_way"))
                        assertEquals(false, document.getBoolean("undefined_codec"))
                        assertEquals(true, document.getBoolean("missed_peer"))

                        assertEquals(MEDIA_CONTROL.callId, document.getString("call_id"))
                        document.getJsonArray("codec_names").toList().let { codecNames ->
                            assertTrue(codecNames.isNotEmpty())
                            assertEquals(RTPR_1.codecName, codecNames.first() as String)
                        }

                        document.getJsonObject("forward_rtp").let { forwardRtp ->
                            assertAddresses(PACKET_1, forwardRtp)

                            assertEquals(RTPR_1.payloadType, forwardRtp.getInteger("payload_type").toByte())
                            assertEquals(RTPR_1.ssrc, forwardRtp.getLong("ssrc"))

                            assertPackets(RTPR_1, forwardRtp.getJsonObject("packets"))
                            assertJitter(RTPR_1, forwardRtp.getJsonObject("jitter"))

                            assertEquals(RTPR_1.rFactor, forwardRtp.getFloat("r_factor"))
                            assertEquals(RTPR_1.mos, forwardRtp.getFloat("mos"))
                            assertEquals(RTPR_1.fractionLost, forwardRtp.getFloat("fraction_lost"))

                            assertEquals(true, document.getBoolean("missed_peer"))
                        }

                        document.getJsonObject("reverse_rtp").let { reverseRtp ->
                            assertAddresses(PACKET_2, reverseRtp)

                            assertEquals(RTPR_2.payloadType, reverseRtp.getInteger("payload_type").toByte())
                            assertEquals(RTPR_2.ssrc, reverseRtp.getLong("ssrc"))

                            assertPackets(RTPR_2, reverseRtp.getJsonObject("packets"))
                            assertJitter(RTPR_2, reverseRtp.getJsonObject("jitter"))

                            assertEquals(RTPR_2.rFactor, reverseRtp.getFloat("r_factor"))
                            assertEquals(RTPR_2.mos, reverseRtp.getFloat("mos"))
                            assertEquals(RTPR_2.fractionLost, reverseRtp.getFloat("fraction_lost"))
                        }
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Receive keep-alive`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaHandler::class, TEST_CONFIG)
            },
            execute = {
                // Send Media Control
                vertx.eventBus().localSend(RoutesCE.media + "_control", MEDIA_CONTROL)

                // Send first Session
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_1)

                vertx.setTimer(300L) {
                    // Send keep alive
                    vertx.eventBus().localSend(RoutesCE.media + "_keep-alive", RTPR_SESSION_1)

                    // Send after 300+400=700ms after first session
                    vertx.setTimer(400L) {
                        vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_2)
                    }
                }

            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_media_index"))

                        assertAddresses(PACKET_1, document)

                        assertEquals(RTPR_SESSION_1.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_SESSION_2.terminatedAt, document.getLong("terminated_at"))

                        assertEquals(2, document.getInteger("report_count"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Update Media Session attributes`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaHandler::class, TEST_CONFIG)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_1)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
                    val (prefix, attributes) = event.body()

                    context.verify {
                        assertEquals("media", prefix)
                        assertEquals(6, attributes.size)
                        assertEquals(12.0, attributes[Attributes.r_factor])
                        assertEquals(13.0, attributes[Attributes.mos])
                        assertEquals(false, attributes[Attributes.one_way])
                        assertEquals(false, attributes[Attributes.undefined_codec])
                        assertEquals(true, attributes[Attributes.missed_peer])
                        assertEquals(0.0, attributes[Attributes.bad_report_fraction])
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Calculate metrics for Media Session`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
            deploy = {
                vertx.deployTestVerticle(MediaHandler::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("expiration-delay", 100L)
                        put("aggregation-timeout", 200L)
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.media + "_control", MEDIA_CONTROL)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_1)
                vertx.eventBus().localSend(RoutesCE.media + "_0", RTPR_SESSION_2)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, _) = event.body()

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_media_index"))
                    }
                }

                vertx.setPeriodic(200L) {
                    registry.find("media_reports").summaries()
                        .firstOrNull { it.mean().toFloat() == 2F }
                        ?.let { summary ->
                            context.verify {
                                val tags = summary.id.tags
                                assertTrue(tags.isNotEmpty())
                                assertTrue(tags.any { it.key == "src_host" })
                                assertTrue(tags.any { tag ->
                                    tag.key == "codec" && tag.value == MEDIA_CONTROL.sdpSession.codecs.first().name
                                })
                            }
                            context.completeNow()
                        }
                }
            }
        )
    }

    private fun assertAddresses(packet: Packet, document: JsonObject) {
        assertEquals(packet.srcAddr.addr, document.getString("src_addr"))
        assertEquals(packet.srcAddr.port, document.getInteger("src_port"))
        assertEquals(packet.dstAddr.addr, document.getString("dst_addr"))
        assertEquals(packet.dstAddr.port, document.getInteger("dst_port"))
    }

    private fun assertPackets(rtpr: RtpReportPayload, packets: JsonObject) {
        assertEquals(rtpr.expectedPacketCount, packets.getInteger("expected"))
        assertEquals(rtpr.receivedPacketCount, packets.getInteger("received"))
        assertEquals(rtpr.lostPacketCount, packets.getInteger("lost"))
        assertEquals(rtpr.rejectedPacketCount, packets.getInteger("rejected"))
    }

    private fun assertJitter(rtpr: RtpReportPayload, jitter: JsonObject) {
        assertEquals(rtpr.lastJitter, jitter.getDouble("last").toFloat())
        assertEquals(rtpr.avgJitter, jitter.getDouble("avg").toFloat())
        assertEquals(rtpr.minJitter, jitter.getDouble("min").toFloat())
        assertEquals(rtpr.maxJitter, jitter.getDouble("max").toFloat())
    }
}
