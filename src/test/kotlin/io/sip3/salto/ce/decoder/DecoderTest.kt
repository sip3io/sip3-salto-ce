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

package io.sip3.salto.ce.decoder

import io.sip3.commons.PacketTypes
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.buffer.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class DecoderTest : VertxTest() {

    companion object {

        // Payload: SIP3 (Version - 2 not compressed)
        val PACKET_1 = byteArrayOf(
            0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x33.toByte(), 0x02.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x3b.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x68.toByte(), 0xdc.toByte(), 0x0f.toByte(), 0xaa.toByte(), 0xd4.toByte(),
            0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x00.toByte(), 0x07.toByte(), 0x7c.toByte(), 0xad.toByte(), 0xd9.toByte(), 0x6b.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0xe5.toByte(), 0x23.toByte(), 0xc1.toByte(), 0xc9.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x05.toByte(), 0x2b.toByte(), 0xe4.toByte(), 0x06.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x0d.toByte(), 0xcf.toByte(), 0x07.toByte(), 0x00.toByte(), 0x04.toByte(), 0x03.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x09.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(),
            0x54.toByte(), 0x45.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x3b.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x68.toByte(), 0xdc.toByte(),
            0x0f.toByte(), 0xaa.toByte(), 0xd4.toByte(), 0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x07.toByte(), 0x7c.toByte(),
            0xad.toByte(), 0xd9.toByte(), 0x6b.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0xe5.toByte(),
            0x23.toByte(), 0xc1.toByte(), 0xc9.toByte(), 0x05.toByte(), 0x00.toByte(), 0x05.toByte(), 0x2b.toByte(),
            0xe4.toByte(), 0x06.toByte(), 0x00.toByte(), 0x05.toByte(), 0x0d.toByte(), 0xcf.toByte(), 0x07.toByte(),
            0x00.toByte(), 0x04.toByte(), 0x03.toByte(), 0x08.toByte(), 0x00.toByte(), 0x09.toByte(), 0x49.toByte(),
            0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(), 0x45.toByte()
        )

        // Payload: SIP3 (Version - 2 compressed)
        val PACKET_2 = byteArrayOf(
            0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x33.toByte(), 0x02.toByte(), 0x01.toByte(), 0x78.toByte(),
            0x9c.toByte(), 0x63.toByte(), 0x64.toByte(), 0x64.toByte(), 0xb0.toByte(), 0x66.toByte(), 0x64.toByte(),
            0xe0.toByte(), 0x66.toByte(), 0x60.toByte(), 0x60.toByte(), 0xcc.toByte(), 0xb8.toByte(), 0xc3.toByte(),
            0xbf.toByte(), 0xea.toByte(), 0x0a.toByte(), 0x13.toByte(), 0x03.toByte(), 0x3b.toByte(), 0x03.toByte(),
            0x10.toByte(), 0x30.toByte(), 0x33.toByte(), 0xb0.toByte(), 0xd7.toByte(), 0xac.toByte(), 0xbd.toByte(),
            0x99.toByte(), 0xcd.toByte(), 0xc2.toByte(), 0xc0.toByte(), 0xfe.toByte(), 0x54.toByte(), 0xf9.toByte(),
            0xe0.toByte(), 0x49.toByte(), 0x56.toByte(), 0x06.toByte(), 0x56.toByte(), 0xed.toByte(), 0x27.toByte(),
            0x6c.toByte(), 0x0c.toByte(), 0xac.toByte(), 0xbc.toByte(), 0xe7.toByte(), 0xd9.toByte(), 0x19.toByte(),
            0x58.toByte(), 0x98.toByte(), 0x39.toByte(), 0x18.toByte(), 0x38.toByte(), 0x3d.toByte(), 0xfd.toByte(),
            0xc2.toByte(), 0x3c.toByte(), 0x43.toByte(), 0x5c.toByte(), 0x19.toByte(), 0xc9.toByte(), 0xd7.toByte(),
            0x0a.toByte(), 0x00.toByte(), 0x70.toByte(), 0xc5.toByte(), 0x18.toByte(), 0x4d.toByte()
        )
        
        val PACKET_3 = byteArrayOf(
            0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x33.toByte(), 0x02.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x39.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x8d.toByte(), 0x8d.toByte(), 0x70.toByte(), 0xe7.toByte(), 0x77.toByte(), 0x02.toByte(), 0x00.toByte(), 0x07.toByte(),
            0x00.toByte(), 0x08.toByte(), 0x93.toByte(), 0x50.toByte(), 0x03.toByte(), 0x00.toByte(), 0x07.toByte(), 0x60.toByte(),
            0x57.toByte(), 0x05.toByte(), 0x61.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0x0a.toByte(), 0xb0.toByte(),
            0x5a.toByte(), 0xc3.toByte(), 0x05.toByte(), 0x00.toByte(), 0x05.toByte(), 0x56.toByte(), 0x46.toByte(), 0x06.toByte(),
            0x00.toByte(), 0x05.toByte(), 0x88.toByte(), 0xf4.toByte(), 0x07.toByte(), 0x00.toByte(), 0x04.toByte(), 0x03.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x07.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte()
        )
    }

    @Test
    fun `Decode SIP3 SIP packet with protocol version 2 not compressed`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(Decoder::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.sip3, Pair(sender, Buffer.buffer(PACKET_1)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, List<Packet>>>(RoutesCE.router) { event ->
                    val (_, packets) = event.body()
                    context.verify {
                        assertEquals(2, packets.size)
                        assertPacket(packets[0])
                        assertPacket(packets[1])
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Decode SIP3 SIP packet with protocol version 2 compressed`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(Decoder::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.sip3, Pair(sender, Buffer.buffer(PACKET_2)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, List<Packet>>>(RoutesCE.router) { event ->
                    val (_, packets) = event.body()
                    context.verify {
                        assertEquals(2, packets.size)
                        assertPacket(packets[0])
                        assertPacket(packets[1])
                    }
                    context.completeNow()
                }
            }
        )
    }
    @Test
    fun `Decode SIP3 SIP packet with protocol version 2 2`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(Decoder::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.sip3, Pair(sender, Buffer.buffer(PACKET_3)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, List<Packet>>>(RoutesCE.router) { event ->
                    val (_, packets) = event.body()
                    context.verify {
                        assertEquals(1, packets.size)
                        val packet = packets[0]
                        assertEquals(1549880240852L, packet.createdAt)
                        assertEquals(0, packet.nanos)
                        assertEquals(PacketTypes.RAW, packet.type)

                        val payload = String(packet.payload.copyOfRange(3, packet.payload.size))
                        assertEquals("HEP3 HEP3HEP3", payload)
                    }
                    context.completeNow()
                }
            }
        )
    }

    private fun assertPacket(packet: Packet) {
        assertEquals(1549880240852, packet.createdAt)
        assertEquals(0, packet.nanos)
        val src = packet.srcAddr
        assertEquals("124.173.217.107", src.addr)
        assertEquals(11236, src.port)
        val dst = packet.dstAddr
        assertEquals("229.35.193.201", dst.addr)
        assertEquals(3535, dst.port)
        assertEquals("sip3", packet.source)
        assertEquals("INVITE", packet.payload.toString(Charset.defaultCharset()))
    }
}