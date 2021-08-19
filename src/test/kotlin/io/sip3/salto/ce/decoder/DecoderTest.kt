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

package io.sip3.salto.ce.decoder

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
            0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x32.toByte(), 0xc8.toByte(), 0x7d.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x00.toByte(), 0x07.toByte(), 0x7c.toByte(), 0xad.toByte(), 0xd9.toByte(), 0x6b.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0xe5.toByte(), 0x23.toByte(), 0xc1.toByte(), 0xc9.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x05.toByte(), 0x2b.toByte(), 0xe4.toByte(), 0x06.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x0d.toByte(), 0xcf.toByte(), 0x07.toByte(), 0x00.toByte(), 0x04.toByte(), 0x03.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x09.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(),
            0x54.toByte(), 0x45.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x3b.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x68.toByte(), 0xdc.toByte(),
            0x0f.toByte(), 0xaa.toByte(), 0xd4.toByte(), 0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x32.toByte(),
            0xc8.toByte(), 0x7d.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x07.toByte(), 0x7c.toByte(),
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
            0xbf.toByte(), 0xea.toByte(), 0x0a.toByte(), 0x13.toByte(), 0x03.toByte(), 0xbb.toByte(), 0xd1.toByte(),
            0x89.toByte(), 0x5a.toByte(), 0x06.toByte(), 0x66.toByte(), 0x06.toByte(), 0xf6.toByte(), 0x9a.toByte(),
            0xb5.toByte(), 0x37.toByte(), 0xb3.toByte(), 0x59.toByte(), 0x18.toByte(), 0xd8.toByte(), 0x9f.toByte(),
            0x2a.toByte(), 0x1f.toByte(), 0x3c.toByte(), 0xc9.toByte(), 0xca.toByte(), 0xc0.toByte(), 0xaa.toByte(),
            0xfd.toByte(), 0x84.toByte(), 0x8d.toByte(), 0x81.toByte(), 0x95.toByte(), 0xf7.toByte(), 0x3c.toByte(),
            0x3b.toByte(), 0x03.toByte(), 0x0b.toByte(), 0x33.toByte(), 0x07.toByte(), 0x03.toByte(), 0xa7.toByte(),
            0xa7.toByte(), 0x5f.toByte(), 0x98.toByte(), 0x67.toByte(), 0x88.toByte(), 0x2b.toByte(), 0x23.toByte(),
            0xf9.toByte(), 0x5a.toByte(), 0x01.toByte(), 0x3b.toByte(), 0xdb.toByte(), 0x1b.toByte(), 0x3b.toByte()
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

    private fun assertPacket(packet: Packet) {
        assertEquals(1549880240852, packet.timestamp.time)
        assertEquals(852000000, packet.timestamp.nanos)
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