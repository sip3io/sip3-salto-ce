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

package io.sip3.salto.ce.rtcp

import io.sip3.commons.PacketTypes
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class RtcpHandlerTest : VertxTest() {

    companion object {

        // RTCP Sender Report only
        val PACKET_1 = byteArrayOf(
            // SR
            0x81.toByte(), 0xc8.toByte(), 0x00.toByte(), 0x0c.toByte(), // Payload type & report count & length
            0x01.toByte(), 0xa8.toByte(), 0xbd.toByte(), 0xe3.toByte(), // SSRC
            0xe1.toByte(), 0x37.toByte(), 0x70.toByte(), 0x16.toByte(), // Timestamp MSW
            0xd0.toByte(), 0x84.toByte(), 0x1e.toByte(), 0xde.toByte(), // Timestamp LSW
            0x9f.toByte(), 0x3a.toByte(), 0x5d.toByte(), 0x06.toByte(), // RTP Timestamp
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xc3.toByte(), // Senders packet count
            0x00.toByte(), 0x00.toByte(), 0x79.toByte(), 0xe0.toByte(), // Senders octet count
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // 1st report block ssrc
            0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), // Fraction Lost & cumulative packet lost
            0x00.toByte(), 0x00.toByte(), 0x2c.toByte(), 0x21.toByte(), // Extended sequence number
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1c.toByte(), // Interarrival jitter
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // LSR Timestamp
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()  // Delay since last SR
        )

        // RTCP Sender Report and Source description
        val PACKET_2 = byteArrayOf(
            // SR
            0x81.toByte(), 0xc8.toByte(), 0x00.toByte(), 0x0c.toByte(),
            0x01.toByte(), 0xa8.toByte(), 0xbd.toByte(), 0xe3.toByte(),
            0xe1.toByte(), 0x37.toByte(), 0x70.toByte(), 0x1a.toByte(),
            0xd5.toByte(), 0xa3.toByte(), 0x6e.toByte(), 0x2e.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xfa.toByte(), 0xa0.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x8c.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xf7.toByte(), 0x80.toByte(),
            0x00.toByte(), 0x8e.toByte(), 0xb0.toByte(), 0x44.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x2c.toByte(), 0xea.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x37.toByte(),
            0x70.toByte(), 0x1a.toByte(), 0x03.toByte(), 0xd7.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x01.toByte(),

            // SD
            0x81.toByte(), 0xca.toByte(), 0x00.toByte(), 0x0a.toByte(),
            0x23.toByte(), 0x43.toByte(), 0x50.toByte(), 0x1f.toByte(),
            0x01.toByte(), 0x07.toByte(), 0x4d.toByte(), 0x59.toByte(),
            0x43.toByte(), 0x4e.toByte(), 0x41.toByte(), 0x4d.toByte(),
            0x45.toByte(), 0x05.toByte(), 0x08.toByte(), 0x61.toByte(),
            0x17.toByte(), 0x01.toByte(), 0x40.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07.toByte(),
            0x07.toByte(), 0xf8.toByte(), 0xfa.toByte(), 0xdf.toByte(),
            0xad.toByte(), 0x60.toByte(), 0x12.toByte(), 0x13.toByte(),
            0x08.toByte(), 0x02.toByte(), 0xdf.toByte(), 0xad.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )

        // RTCP Sender Report and Source Description is first
        val PACKET_3 = byteArrayOf(
            // SD
            0x81.toByte(), 0xca.toByte(), 0x00.toByte(), 0x0a.toByte(),
            0x23.toByte(), 0x43.toByte(), 0x50.toByte(), 0x1f.toByte(),
            0x01.toByte(), 0x07.toByte(), 0x4d.toByte(), 0x59.toByte(),
            0x43.toByte(), 0x4e.toByte(), 0x41.toByte(), 0x4d.toByte(),
            0x45.toByte(), 0x05.toByte(), 0x08.toByte(), 0x61.toByte(),
            0x17.toByte(), 0x01.toByte(), 0x40.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07.toByte(),
            0x07.toByte(), 0xf8.toByte(), 0xfa.toByte(), 0xdf.toByte(),
            0xad.toByte(), 0x60.toByte(), 0x12.toByte(), 0x13.toByte(),
            0x08.toByte(), 0x02.toByte(), 0xdf.toByte(), 0xad.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),

            // SR
            0x81.toByte(), 0xc8.toByte(), 0x00.toByte(), 0x0c.toByte(),
            0x01.toByte(), 0xa8.toByte(), 0xbd.toByte(), 0xe3.toByte(),
            0xe1.toByte(), 0x37.toByte(), 0x70.toByte(), 0x1e.toByte(),
            0xda.toByte(), 0xc1.toByte(), 0x4c.toByte(), 0x66.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x78.toByte(), 0x40.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x55.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x75.toByte(), 0x20.toByte(),
            0x00.toByte(), 0x8e.toByte(), 0xb0.toByte(), 0x44.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x2d.toByte(), 0xb3.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x4a.toByte(),
            0x70.toByte(), 0x1a.toByte(), 0x03.toByte(), 0xd7.toByte(),
            0x00.toByte(), 0x04.toByte(), 0x85.toByte(), 0x1f.toByte()
        )

        val PACKET_4 = """
                        {
                            "sender_information": {
                                "ntp_timestamp_sec": 3811646280,
                                "ntp_timestamp_usec": 210071145,
                                "rtp_timestamp": 1610824168,
                                "packets": 595,
                                "octets": 95200
                            },
                            "ssrc": 2141660700,
                            "type": 202,
                            "report_count": 1,
                            "report_blocks": [{
                                    "source_ssrc": 299816601,
                                    "fraction_lost": 0,
                                    "packets_lost": 1,
                                    "highest_seq_no": 34312,
                                    "ia_jitter": 42,
                                    "lsr": 457573597,
                                    "dlsr": 128451
                                }
                            ],
                            "report_blocks_xr": {
                                "type": 0,
                                "id": 0,
                                "fraction_lost": 0,
                                "fraction_discard": 0,
                                "burst_density": 0,
                                "gap_density": 0,
                                "burst_duration": 0,
                                "gap_duration": 0,
                                "round_trip_delay": 0,
                                "end_system_delay": 0
                            },
                            "sdes_ssrc": 2141660700
                        }
                       """.trimIndent().toByteArray()
    }

    @Test
    fun `Parse RTCP`() {
        runTest(
            deploy = {
                vertx.orCreateContext.config().put("media", JsonObject().apply {
                    put("rtcp", JsonObject().apply {
                        put("expiration-delay", 2000)
                        put("aggregation-timeout", 1500)
                    })
                })
                vertx.deployTestVerticle(RtcpHandler::class, vertx.orCreateContext.config())
            },
            execute = {
                listOf(PACKET_1, PACKET_2, PACKET_3).map { payload ->
                    Packet().apply {
                        srcAddr = Address().apply {
                            addr = "10.250.240.5"
                            port = 12057
                        }
                        dstAddr = Address().apply {
                            addr = "10.150.140.5"
                            port = 13057
                        }
                        source = "sip3"
                        protocolCode = PacketTypes.RTCP
                        this.payload = payload
                        timestamp = Timestamp(System.currentTimeMillis())
                    }
                }.forEach { vertx.eventBus().localSend(RoutesCE.rtcp, it) }
            },
            assert = {
                var packetCount = 0
                vertx.eventBus().consumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_rtcp") { event ->
                    context.verify {
                        val (packet, report) = event.body()
                        packetCount++

                        assertEquals(PacketTypes.RTCP, packet.protocolCode)
                        when (packetCount) {
                            1 -> {
                                assertEquals(195, report.expectedPacketCount)
                                assertEquals(1, report.lostPacketCount)
                                assertEquals(28F, report.lastJitter)
                                assertEquals(28F, report.minJitter)
                                assertEquals(28F, report.maxJitter)
                                assertEquals(28F, report.avgJitter)
                            }
                            2 -> {
                                assertEquals(201, report.expectedPacketCount)
                                assertEquals(0, report.lostPacketCount)
                                assertEquals(55F, report.lastJitter)
                                assertEquals(55F, report.minJitter)
                                assertEquals(55F, report.maxJitter)
                                assertEquals(55F, report.avgJitter)
                            }
                            3 -> {
                                assertEquals(201, report.expectedPacketCount)
                                assertEquals(0, report.lostPacketCount)
                                assertEquals(74F, report.lastJitter)
                                assertEquals(74F, report.minJitter)
                                assertEquals(74F, report.maxJitter)
                                assertEquals(74F, report.avgJitter)
                            }
                        }
                    }

                    if (packetCount == 3) {
                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Parse HEP3 RTCP report`() {
        runTest(
            deploy = {
                vertx.orCreateContext.config().put("media", JsonObject().apply {
                    put("rtcp", JsonObject().apply {
                        put("expiration-delay", 2000)
                        put("aggregation-timeout", 1500)
                    })
                })
                vertx.deployTestVerticle(RtcpHandler::class, vertx.orCreateContext.config())
            },
            execute = {
                val packet = Packet().apply {
                    srcAddr = Address().apply {
                        addr = "10.250.240.5"
                        port = 12057
                    }
                    dstAddr = Address().apply {
                        addr = "10.150.140.5"
                        port = 13057
                    }
                    source = "hep3"
                    protocolCode = PacketTypes.RTCP
                    this.payload = PACKET_4
                    timestamp = Timestamp(System.currentTimeMillis())
                }
                vertx.eventBus().localSend(RoutesCE.rtcp, packet)
            },
            assert = {
                vertx.eventBus().consumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_rtcp") { event ->
                    context.verify {
                        val (packet, report) = event.body()
                        assertEquals(PacketTypes.RTCP, packet.protocolCode)
                        assertEquals(595, report.expectedPacketCount)
                        assertEquals(1, report.lostPacketCount)
                        assertEquals(42F, report.lastJitter)
                        assertEquals(42F, report.minJitter)
                        assertEquals(42F, report.maxJitter)
                        assertEquals(42F, report.avgJitter)

                    }
                    context.completeNow()
                }
            }
        )
    }
}