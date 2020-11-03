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

package io.sip3.salto.ce.media

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localRequest
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

        // Periodic RTP report with RTP source
        val RTPR = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            cumulative = true
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
    }

    @Test
    fun `Write cumulative RTP report to database`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(MediaHandler::class)
                },
                execute = {
                    val session = RtprSession(PACKET_1).apply {
                        add(RTPR)
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.media, session)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        val (collection, operation) = event.body()

                        val document = operation.getJsonObject("document")

                        context.verify {
                            assertTrue(collection.startsWith("rtpr_rtp_index"))

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
    fun `Update cumulative RTP report attributes`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(MediaHandler::class)
                },
                execute = {
                    val session = RtprSession(PACKET_1).apply {
                        add(RTPR)
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.media, session)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
                        val (prefix, attributes) = event.body()

                        context.verify {
                            assertEquals("rtp", prefix)
                            assertEquals(2, attributes.size)
                            assertEquals(12F, attributes[Attributes.r_factor])
                            assertEquals(13F, attributes[Attributes.mos])
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
                    vertx.deployTestVerticle(MediaHandler::class)
                },
                execute = {
                    val session = RtprSession(PACKET_1).apply {
                        add(RTPR)
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.media, session)
                },
                assert = {
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
