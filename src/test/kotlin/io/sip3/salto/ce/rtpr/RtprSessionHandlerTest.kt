/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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
import io.sip3.commons.util.getBytes
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.attributes.AttributesRegistry
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class RtprSessionHandlerTest : VertxTest() {

    companion object {

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

            reportedAt = 1579511172674
            createdAt = 1579522272674
        }

        val PACKET_1 = Packet().apply {
            createdAt = System.currentTimeMillis()
            srcAddr = SRC_ADDR
            dstAddr = DST_ADDR
            payload = RTPR_1.encode().getBytes()
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

            reportedAt = 1579511272674
            createdAt = 1579522372674
        }

        val PACKET_2 = Packet().apply {
            createdAt = System.currentTimeMillis()
            srcAddr = DST_ADDR
            dstAddr = SRC_ADDR
            payload = RTPR_2.encode().getBytes()
        }

        // Media Control
        val MEDIA_CONTROL = MediaControl().apply {
            timestamp = System.currentTimeMillis()

            callId = "callId_uuid@domain.io"
            caller = "123"
            callee = "456"

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

        val RTPR_SESSION = RtprSession.create(RTPR_1.source, MEDIA_CONTROL, PACKET_1).apply {
            add(PACKET_1, RTPR_1)
            add(PACKET_2, RTPR_2)
        }
    }

    @BeforeEach
    fun `Mock all`() {
        mockkConstructor(AttributesRegistry::class)
        every {
            anyConstructed<AttributesRegistry>().handle(any(), any())
            anyConstructed<AttributesRegistry>().handle(any(), any(), any())
        } just Runs
    }

    @Test
    fun `Write RtprSession to database`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprSessionHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_session", RTPR_SESSION)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("rtpr_rtp_index"), collection)

                        assertAddresses(PACKET_1, document)

                        assertEquals(RTPR_SESSION.createdAt, document.getLong("created_at"))
                        assertEquals(RTPR_SESSION.terminatedAt, document.getLong("terminated_at"))

                        assertEquals(2, document.getInteger("report_count"))
                        assertEquals(0, document.getInteger("bad_report_count"))
                        assertEquals(0, document.getInteger("bad_report_fraction"))

                        assertEquals(12.0, document.getJsonArray("r_factor").first())
                        assertEquals(13.0, document.getJsonArray("mos").first())


                        assertEquals(false, document.getBoolean("one_way"))

                        assertEquals(MEDIA_CONTROL.callId, document.getString("call_id"))
                        assertEquals(MEDIA_CONTROL.caller, document.getString("caller"))
                        assertEquals(MEDIA_CONTROL.callee, document.getString("callee"))

                        assertEquals("out", document.getJsonArray("direction").first())
                        document.getJsonArray("codec").toList().let { codecs ->
                            assertTrue(codecs.isNotEmpty())
                            assertEquals(RTPR_1.codecName, codecs.first())
                        }
                        assertEquals(RTPR_1.payloadType, (document.getJsonArray("payload_type").first() as Int).toByte())
                        assertEquals(RTPR_1.ssrc, document.getJsonArray("ssrc").first() as Long)

                        assertPackets(RTPR_1, document.getJsonObject("packets"), 0)
                        assertJitter(RTPR_1, document.getJsonObject("jitter"), 0)

                        assertEquals(RTPR_1.rFactor, (document.getJsonArray("r_factor").first() as Double).toFloat())
                        assertEquals(RTPR_1.mos, (document.getJsonArray("mos").first() as Double).toFloat())
                        assertEquals(RTPR_1.fractionLost, (document.getJsonArray("fraction_lost").first() as Double).toFloat())

                        assertEquals("in", document.getJsonArray("direction").last())
                        assertEquals(RTPR_2.payloadType, (document.getJsonArray("payload_type").last() as Int).toByte())
                        assertEquals(RTPR_2.ssrc, document.getJsonArray("ssrc").last() as Long)

                        assertPackets(RTPR_2, document.getJsonObject("packets"), 1)
                        assertJitter(RTPR_2, document.getJsonObject("jitter"), 1)

                        assertEquals(RTPR_2.rFactor, (document.getJsonArray("r_factor").last() as Double).toFloat())
                        assertEquals(RTPR_2.mos, (document.getJsonArray("mos").last() as Double).toFloat())
                        assertEquals(RTPR_2.fractionLost, (document.getJsonArray("fraction_lost").last() as Double).toFloat())

                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Calculate metrics for RTP Report Session`() {
        val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, MockClock())
        Metrics.addRegistry(registry)

        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprSessionHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_session", RTPR_SESSION)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, _) = event.body()
                    context.verify {
                        assertTrue(collection.startsWith("rtpr_rtp_index"))
                    }
                }

                vertx.setPeriodic(200L) {
                    registry.find("rtpr_rtp_reports").summaries()
                        .firstOrNull { it.mean() == 2.0 }
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

    @Test
    fun `Handle session attributes`() {
        val prefixSlot = slot<String>()
        val attributesSlot = slot<Map<String, Any>>()
        every {
            anyConstructed<AttributesRegistry>().handle(capture(prefixSlot), capture(attributesSlot))
        } just Runs

        runTest(
            deploy = {
                vertx.deployTestVerticle(RtprSessionHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rtpr + "_session", RTPR_SESSION)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) {
                    context.verify {
                        assertEquals("rtp", prefixSlot.captured)

                        val attributes = attributesSlot.captured
                        assertEquals(7, attributes.size)
                        assertEquals("", attributes["src_addr"])
                        assertEquals("SomeHost", attributes["src_host"])
                        assertEquals("", attributes["dst_addr"])
                        assertEquals(13.0, attributes["mos"])
                        assertEquals(12.0, attributes["r_factor"])
                        assertEquals(0.0, attributes["bad_report_fraction"])
                        assertEquals(false, attributes["one_way"])
                    }
                    context.completeNow()
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

    private fun assertPackets(rtpr: RtpReportPayload, packets: JsonObject, index: Int) {
        assertEquals(rtpr.expectedPacketCount, packets.getJsonArray("expected")[index])
        assertEquals(rtpr.receivedPacketCount, packets.getJsonArray("received")[index])
        assertEquals(rtpr.lostPacketCount, packets.getJsonArray("lost")[index])
        assertEquals(rtpr.rejectedPacketCount, packets.getJsonArray("rejected")[index])
    }

    private fun assertJitter(rtpr: RtpReportPayload, jitter: JsonObject, index: Int) {
        assertEquals(rtpr.lastJitter, (jitter.getJsonArray("last").getDouble(index)).toFloat())
        assertEquals(rtpr.avgJitter, (jitter.getJsonArray("avg").getDouble(index)).toFloat())
        assertEquals(rtpr.minJitter, (jitter.getJsonArray("min").getDouble(index)).toFloat())
        assertEquals(rtpr.maxJitter, (jitter.getJsonArray("max").getDouble(index)).toFloat())
    }

    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}
