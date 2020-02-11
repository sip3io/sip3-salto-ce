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
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class RtprHandlerTest : VertxTest() {

    companion object {

        // Periodic RTP report with RTP source
        val RTPR = RtpReportPayload().apply {
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

        // Packet with periodic RTP report with RTP source
        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "10.10.10.10"
                port = 10500
            }
            dstAddr = Address().apply {
                addr = "10.20.20.20"
                port = 20500
            }
            payload = RTPR.encode().array()
        }

        // Packet with cumulative RTP report with RTCP source
        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(System.currentTimeMillis())
            srcAddr = Address().apply {
                addr = "10.10.10.10"
                port = 10500
            }
            dstAddr = Address().apply {
                addr = "10.20.20.20"
                port = 20500
            }
            payload = RtpReportPayload().apply {
                source = RtpReportPayload.SOURCE_RTCP
                cumulative = true
                payloadType = 1
                codecName = "PCMA"
            }.encode().array()
        }
    }

    @Test
    fun `Write periodic RTP report to database`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class)
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.rtpr, PACKET_1, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()

                        document = document.getJsonObject("document")

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_raw"))

                            assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                            assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                            assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                            assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                            assertEquals(RTPR.createdAt, document.getLong("created_at"))
                            assertEquals(RTPR.startedAt, document.getLong("started_at"))

                            assertEquals(RTPR.callId, document.getString("call_id"))
                            assertEquals(RTPR.codecName, document.getString("codec_name"))

                            assertEquals(RTPR.payloadType, document.getInteger("payload_type").toByte())
                            assertEquals(RTPR.ssrc, document.getLong("ssrc"))


                            val packets = document.getJsonObject("packets")
                            assertEquals(RTPR.expectedPacketCount, packets.getInteger("expected"))
                            assertEquals(RTPR.receivedPacketCount, packets.getInteger("received"))
                            assertEquals(RTPR.lostPacketCount, packets.getInteger("lost"))
                            assertEquals(RTPR.rejectedPacketCount, packets.getInteger("rejected"))

                            val jitter = document.getJsonObject("jitter")
                            assertEquals(RTPR.lastJitter, jitter.getDouble("last").toFloat())
                            assertEquals(RTPR.avgJitter, jitter.getDouble("avg").toFloat())
                            assertEquals(RTPR.minJitter, jitter.getDouble("min").toFloat())
                            assertEquals(RTPR.maxJitter, jitter.getDouble("max").toFloat())

                            assertEquals(RTPR.rFactor, document.getFloat("r_factor"))
                            assertEquals(RTPR.mos, document.getFloat("mos"))
                            assertEquals(RTPR.fractionLost, document.getFloat("fraction_lost"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Write cumulative RTCP report to database`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class)
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.rtpr, PACKET_2, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, _) = event.body()

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtcp_index"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Update cumulative RTCP report attributes`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class)
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.rtpr, PACKET_2, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
                        val (prefix, attributes) = event.body()

                        context.verify {
                            assertEquals("rtp", prefix)
                            assertEquals(4, attributes.size)
                            assertEquals("PCMA", attributes["codec_name"])
                            assertEquals("1", attributes["payload_type"])
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Generate QoS metrics per each RTP report`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
                deploy = {
                    vertx.deployTestVerticle(RtprHandler::class, JsonObject().apply {
                        put("rtp-r", JsonObject().apply {
                            put("cumulative-metrics", false)
                        })
                    })
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.rtpr, PACKET_1, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, _) = event.body()

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_raw"))
                        }
                    }

                    vertx.setPeriodic(200L) {
                        registry.find("rtpr_rtp_r-factor").summary()?.let { summary ->
                            context.verify {
                                assertEquals(RTPR.rFactor, summary.mean().toFloat())
                                val tags = summary.id.tags
                                assertTrue(tags.isNotEmpty())
                                assertTrue(tags.any { it.value == RTPR.codecName })
                            }
                            context.completeNow()
                        }
                    }
                }
        )
    }
}
