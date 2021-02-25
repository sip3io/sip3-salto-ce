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
import io.sip3.commons.domain.payload.RecordingPayload
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class RecordingHandlerTest : VertxTest() {

    val RECORDING_1 = RecordingPayload().apply {
        callId = "callId@domain.com"
        type = RecordingPayload.TYPE_RTP_GDPR
        payload = "rtp gdpr packet payload".toByteArray()
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
        type = RecordingPayload.TYPE_RTCP
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
                vertx.deployTestVerticle(RecordingHandler::class)
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

                        assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                        assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                        assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                        assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))

                        assertEquals(RecordingPayload.TYPE_RTP_GDPR.toInt(), document.getInteger("type"))
                        assertEquals(RECORDING_1.callId, document.getString("call_id"))
                        assertArrayEquals(RECORDING_1.payload, document.getBinary("raw_data"))
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