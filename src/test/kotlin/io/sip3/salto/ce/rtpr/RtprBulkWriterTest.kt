/*
 * Copyright 2018-2024 SIP3.IO, Corp.
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

import io.sip3.commons.domain.media.Codec
import io.sip3.commons.domain.media.MediaAddress
import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.getBytes
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RtprBulkWriterTest : VertxTest() {

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
            recorded = false
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

            reportedAt = 1779511172674
            createdAt = 1779511272674
        }

        // Periodic RTP report without Call-ID
        val RTPR_2 = RtpReportPayload().apply {
            source = RtpReportPayload.SOURCE_RTP
            recorded = false
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

            rFactor = 45F
            fractionLost = lostPacketCount / expectedPacketCount.toFloat()

            reportedAt = 1779511178674
            createdAt = 1779511178674
        }


        // Packet with periodic RTP report with RTP source
        val PACKET_1 = Packet().apply {
            createdAt = System.currentTimeMillis()
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_1.encode().getBytes()
        }

        // Packet with periodic RTP report with RTP source
        val PACKET_2 = Packet().apply {
            createdAt = System.currentTimeMillis()
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_2.encode().getBytes()
        }

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "callId_uuid@domain.io"

            sdpSession = SdpSession().apply {
                src = MediaAddress().apply {
                    addr = SRC_ADDR.addr
                    rtpPort = SRC_ADDR.port
                    rtcpPort = SRC_ADDR.port
                }
                dst = MediaAddress().apply {
                    addr = DST_ADDR.addr
                    rtpPort = SRC_ADDR.port
                    rtcpPort = SRC_ADDR.port
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
    fun `Write periodic RTP report from RTP to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprBulkWriter::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 20000L)
                            put("bulk_packet_limit", 150L)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_bulk_writer", Pair(PACKET_1, RTPR_1))
                vertx.eventBus().localSend(RoutesCE.rtpr + "_bulk_writer", Pair(PACKET_2, RTPR_2))
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

                        assertEquals(RTPR_1.reportedAt, document.getLong("reported_at"))
                        assertEquals(RTPR_1.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_1.callId, document.getString("call_id"))

                        val reports = document.getJsonArray("reports")
                        assertEquals(2, reports.size())

                        assertReport(RTPR_1, reports.getJsonObject(0))
                        assertReport(RTPR_2, reports.getJsonObject(1))
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Write periodic RTP report from RTP to database by expected packet limit`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprBulkWriter::class, JsonObject().apply {
                    put("media", JsonObject().apply {
                        put("rtp_r", JsonObject().apply {
                            put("expiration_delay", 100L)
                            put("aggregation_timeout", 20000L)
                            put("bulk_packet_limit", 90L)
                        })
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_bulk_writer", Pair(PACKET_1, RTPR_1))
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

                        assertEquals(RTPR_1.reportedAt, document.getLong("reported_at"))
                        assertEquals(RTPR_1.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_1.callId, document.getString("call_id"))

                        val reports = document.getJsonArray("reports")
                        assertEquals(1, reports.size())

                        assertReport(RTPR_1, reports.getJsonObject(0))
                    }

                    context.completeNow()
                }
            }
        )
    }

    private fun assertReport(origin: RtpReportPayload, document: JsonObject) {
        assertEquals(origin.reportedAt, document.getLong("reported_at"))
        assertEquals(origin.createdAt, document.getLong("created_at"))

        assertEquals(origin.codecName, document.getString("codec"))

        assertEquals(origin.payloadType, document.getInteger("payload_type").toByte())
        assertEquals(origin.ssrc, document.getLong("ssrc"))

        document.getJsonObject("packets").apply {
            assertEquals(origin.expectedPacketCount, getInteger("expected"))
            assertEquals(origin.receivedPacketCount, getInteger("received"))
            assertEquals(origin.lostPacketCount, getInteger("lost"))
            assertEquals(origin.rejectedPacketCount, getInteger("rejected"))
        }

        document.getJsonObject("jitter").apply {
            assertEquals(origin.lastJitter, getDouble("last").toFloat())
            assertEquals(origin.avgJitter, getDouble("avg").toFloat())
            assertEquals(origin.minJitter, getDouble("min").toFloat())
            assertEquals(origin.maxJitter, getDouble("max").toFloat())
        }

        assertEquals(origin.rFactor, document.getFloat("r_factor"))
        assertEquals(origin.mos, document.getFloat("mos"))
        assertEquals(origin.fractionLost, document.getFloat("fraction_lost"))
    }
}
