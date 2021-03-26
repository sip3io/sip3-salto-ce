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

import io.sip3.commons.PacketTypes
import io.sip3.commons.domain.media.Recording
import io.sip3.commons.domain.payload.RecordingPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class RecordingHandlerTest : VertxTest() {

    val RECORDING_1 = RecordingPayload().apply {
        callId = "callId@domain.com"
        type = PacketTypes.RTP
        mode = Recording.FULL
        payload = byteArrayOf(
            0x80.toByte(), 0x08.toByte(), 0x1c.toByte(), 0xf9.toByte(), 0x99.toByte(), 0x75.toByte(), 0xd5.toByte(), 0x78.toByte(),
            0x30.toByte(), 0xe8.toByte(), 0x71.toByte(), 0x2d.toByte(), 0xcc.toByte(), 0xf5.toByte(), 0xf7.toByte(), 0xf1.toByte(),
            0xf1.toByte(), 0xf1.toByte(), 0xf0.toByte(), 0xf1.toByte(), 0xf1.toByte(), 0xf0.toByte(), 0xf3.toByte(), 0xf3.toByte(),
            0xf2.toByte(), 0xfc.toByte(), 0xf2.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xf0.toByte(), 0xf6.toByte(), 0xca.toByte(),
            0xc5.toByte(), 0xd0.toByte(), 0x5c.toByte(), 0x4e.toByte(), 0x70.toByte(), 0x79.toByte(), 0x64.toByte(), 0x66.toByte(),
            0x61.toByte(), 0x63.toByte(), 0x60.toByte(), 0x66.toByte(), 0x67.toByte(), 0x7b.toByte(), 0x72.toByte(), 0x75.toByte(),
            0x44.toByte(), 0x57.toByte(), 0xdd.toByte(), 0xc1.toByte(), 0xcb.toByte(), 0xf4.toByte(), 0xf6.toByte(), 0xf0.toByte(),
            0xf1.toByte(), 0xf1.toByte(), 0xf1.toByte(), 0xf6.toByte(), 0xf6.toByte(), 0xf1.toByte(), 0xf6.toByte(), 0xf1.toByte(),
            0xf3.toByte(), 0xf0.toByte(), 0xf3.toByte(), 0xf3.toByte(), 0xf1.toByte(), 0xf7.toByte(), 0xc8.toByte(), 0xc1.toByte(),
            0xdd.toByte(), 0x51.toByte(), 0x40.toByte(), 0x74.toByte(), 0x7d.toByte(), 0x7b.toByte(), 0x64.toByte(), 0x67.toByte(),
            0x61.toByte(), 0x61.toByte(), 0x66.toByte(), 0x64.toByte(), 0x79.toByte(), 0x73.toByte(), 0x75.toByte(), 0x44.toByte(),
            0x56.toByte(), 0xd2.toByte(), 0xc7.toByte(), 0xce.toByte(), 0xf5.toByte(), 0xf7.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf7.toByte(), 0xf7.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf5.toByte(), 0xf4.toByte(), 0xf4.toByte(), 0xf7.toByte(),
            0xf7.toByte(), 0xf6.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xc3.toByte(), 0xdb.toByte(),
            0xd6.toByte(), 0x5e.toByte(), 0x4c.toByte(), 0x71.toByte(), 0x7f.toByte(), 0x65.toByte(), 0x66.toByte(), 0x61.toByte(),
            0x60.toByte(), 0x61.toByte(), 0x66.toByte(), 0x65.toByte(), 0x7e.toByte(), 0x71.toByte(), 0x4c.toByte(), 0x5c.toByte(),
            0xd0.toByte(), 0xc1.toByte(), 0xca.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xfd.toByte(), 0xf3.toByte(), 0xf0.toByte(), 0xf1.toByte(), 0xf7.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf5.toByte(),
            0xcb.toByte(), 0xca.toByte(), 0xce.toByte(), 0xcf.toByte(), 0xc2.toByte(), 0xc5.toByte(), 0xdc.toByte(), 0xd4.toByte(),
            0x5c.toByte(), 0x40.toByte(), 0x75.toByte(), 0x72.toByte(), 0x7e.toByte(), 0x65.toByte(), 0x64.toByte(), 0x67.toByte(),
            0x67.toByte(), 0x64.toByte(), 0x65.toByte(), 0x79.toByte(), 0x72.toByte(), 0x75.toByte(), 0x46.toByte(), 0x51.toByte(),
            0xd3.toByte(), 0xc6.toByte(), 0xc8.toByte(), 0xf4.toByte()
        )
    }

    val PACKET_1 = Packet().apply {
        timestamp = Timestamp(System.currentTimeMillis())

        srcAddr = Address().apply {
            addr = "127.0.0.1"
            port = 10000
            host = "SRC_HOST"
        }

        dstAddr = Address().apply {
            addr = "127.0.0.2"
            port = 20000
        }

        source = "sip3"
        protocolCode = PacketTypes.REC
        payload = RECORDING_1.encode().array()
    }

    val RECORDING_2 = RecordingPayload().apply {
        callId = "callId@domain.com"
        type = PacketTypes.RTCP
        mode = Recording.FULL
        payload = "rtcp packet payload".toByteArray()
    }

    val PACKET_2 = Packet().apply {
        timestamp = Timestamp(System.currentTimeMillis())

        srcAddr = Address().apply {
            addr = "127.0.0.1"
            port = 10000
            host = "SRC_HOST"
        }

        dstAddr = Address().apply {
            addr = "127.0.0.2"
            port = 20000
        }

        source = "sip3"
        protocolCode = PacketTypes.REC
        payload = RECORDING_2.encode().array()
    }


    @Test
    fun `Receive 'RTP' packet and write to DB`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RecordingHandler::class, JsonObject().apply {
                    put("recording", JsonObject().apply {
                        put("bulk-size", 1)
                    })
                })
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rec, PACKET_1)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()
                    val document = operation.getJsonObject("document")
                    context.verify {
                        assertTrue(collection.startsWith("rec_raw"))

                        assertEquals(PACKET_1.timestamp.time, document.getLong("created_at"))

                        assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                        assertEquals(RECORDING_1.callId, document.getString("call_id"))

                        assertEquals(1, document.getJsonArray("packets").size())
                        val packet = document.getJsonArray("packets").first() as JsonObject
                        assertEquals(PacketTypes.RTP.toInt(), packet.getInteger("type"))
                        assertEquals(RECORDING_1.payload.toString(Charsets.ISO_8859_1), packet.getString("raw_data"))
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Receive packets and write to DB as bulk`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RecordingHandler::class, JsonObject().apply {
                    put("recording", JsonObject().apply {
                        put("bulk-size", 3)
                    })
                })
            },
            execute = {
                repeat(4) {
                    vertx.eventBus().localSend(RoutesCE.rec, PACKET_1)
                }
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()
                    val document = operation.getJsonObject("document")
                    context.verify {
                        assertTrue(collection.startsWith("rec_raw"))

                        assertEquals(PACKET_1.timestamp.time, document.getLong("created_at"))

                        assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                        assertEquals(RECORDING_1.callId, document.getString("call_id"))

                        assertEquals(3, document.getJsonArray("packets").size())
                        val packet = document.getJsonArray("packets").first() as JsonObject

                        assertEquals(PacketTypes.RTP.toInt(), packet.getInteger("type"))
                        assertEquals(RECORDING_1.payload.toString(Charsets.ISO_8859_1), packet.getString("raw_data"))
                    }

                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Receive 'RTCP' packet and send to RtcpHandler`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(RecordingHandler::class)
            },
            execute = {
                vertx.eventBus().localSend(RoutesCE.rec, PACKET_2)
            },
            assert = {
                vertx.eventBus().consumer<Packet>(RoutesCE.rtcp) { event ->
                    val packet = event.body()
                    context.verify {
                        assertEquals(PACKET_2.timestamp, packet.timestamp)
                        assertEquals(PACKET_2.srcAddr, packet.srcAddr)
                        assertEquals(PACKET_2.dstAddr, packet.dstAddr)
                        assertEquals(PacketTypes.RTCP, packet.protocolCode)
                        assertEquals("sip3", packet.source)
                        assertArrayEquals(RECORDING_2.payload, packet.payload)
                    }
                    context.completeNow()
                }
            }
        )
    }
}