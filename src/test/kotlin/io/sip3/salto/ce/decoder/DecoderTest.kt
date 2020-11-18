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

package io.sip3.salto.ce.decoder

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.buffer.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class DecoderTest : VertxTest() {

    companion object {

        // Payload: SIP3 (not compressed)
        val PACKET_1 = byteArrayOf(
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x33.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x3b.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x01.toByte(), 0x68.toByte(), 0xdc.toByte(), 0x0f.toByte(), 0xaa.toByte(), 0xd4.toByte(), 0x02.toByte(),
                0x00.toByte(), 0x07.toByte(), 0x32.toByte(), 0xc8.toByte(), 0x7d.toByte(), 0x00.toByte(), 0x03.toByte(),
                0x00.toByte(), 0x07.toByte(), 0x7c.toByte(), 0xad.toByte(), 0xd9.toByte(), 0x6b.toByte(), 0x04.toByte(),
                0x00.toByte(), 0x07.toByte(), 0xe5.toByte(), 0x23.toByte(), 0xc1.toByte(), 0xc9.toByte(), 0x05.toByte(),
                0x00.toByte(), 0x05.toByte(), 0x2b.toByte(), 0xe4.toByte(), 0x06.toByte(), 0x00.toByte(), 0x05.toByte(),
                0x0d.toByte(), 0xcf.toByte(), 0x07.toByte(), 0x00.toByte(), 0x04.toByte(), 0x03.toByte(), 0x08.toByte(),
                0x00.toByte(), 0x09.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(),
                0x45.toByte()
        )

        // Payload: SIP3 (compressed)
        val PACKET_2 = byteArrayOf(
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x33.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x43.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x01.toByte(), 0x68.toByte(), 0xdc.toByte(), 0x0f.toByte(), 0xaa.toByte(), 0xd4.toByte(), 0x02.toByte(),
                0x00.toByte(), 0x07.toByte(), 0x32.toByte(), 0xc8.toByte(), 0x7d.toByte(), 0x00.toByte(), 0x03.toByte(),
                0x00.toByte(), 0x07.toByte(), 0x7c.toByte(), 0xad.toByte(), 0xd9.toByte(), 0x6b.toByte(), 0x04.toByte(),
                0x00.toByte(), 0x07.toByte(), 0xe5.toByte(), 0x23.toByte(), 0xc1.toByte(), 0xc9.toByte(), 0x05.toByte(),
                0x00.toByte(), 0x05.toByte(), 0x2b.toByte(), 0xe4.toByte(), 0x06.toByte(), 0x00.toByte(), 0x05.toByte(),
                0x0d.toByte(), 0xcf.toByte(), 0x07.toByte(), 0x00.toByte(), 0x04.toByte(), 0x03.toByte(), 0x08.toByte(),
                0x00.toByte(), 0x11.toByte(), 0x78.toByte(), 0x9c.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0x0b.toByte(),
                0xf3.toByte(), 0x0c.toByte(), 0x71.toByte(), 0x05.toByte(), 0x00.toByte(), 0x06.toByte(), 0x62.toByte(),
                0x01.toByte(), 0xd0.toByte()
        )
    }

    @Test
    fun `Decode SIP3 SIP packet with not compressed`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Decoder::class)
                },
                execute = {
                    val sender = Address().apply {
                        addr = "127.0.0.1"
                        port = 5060
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.sip3, Pair(sender, Buffer.buffer(PACKET_1)))
                },
                assert = {
                    vertx.eventBus().consumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
                        val (_, packet) = event.body()
                        context.verify {
                            assertEquals("sip3", packet.source)
                            assertEquals(1549880240852, packet.timestamp.time)
                            assertEquals(852000000, packet.timestamp.nanos)
                            val src = packet.srcAddr
                            assertEquals("124.173.217.107", src.addr)
                            assertEquals(11236, src.port)
                            val dst = packet.dstAddr
                            assertEquals("229.35.193.201", dst.addr)
                            assertEquals(3535, dst.port)
                            assertEquals("INVITE", packet.payload.toString(Charset.defaultCharset()))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Decode SIP3 SIP packet with compressed`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Decoder::class)
                },
                execute = {
                    val sender = Address().apply {
                        addr = "127.0.0.1"
                        port = 5060
                    }
                    vertx.eventBus().localRequest<Any>(RoutesCE.sip3, Pair(sender, Buffer.buffer(PACKET_2)))
                },
                assert = {
                    vertx.eventBus().consumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
                        val (_, packet) = event.body()
                        context.verify {
                            assertEquals("sip3", packet.source)
                            assertEquals(1549880240852, packet.timestamp.time)
                            assertEquals(852000000, packet.timestamp.nanos)
                            val src = packet.srcAddr
                            assertEquals("124.173.217.107", src.addr)
                            assertEquals(11236, src.port)
                            val dst = packet.dstAddr
                            assertEquals("229.35.193.201", dst.addr)
                            assertEquals(3535, dst.port)
                            assertEquals("INVITE", packet.payload.toString(Charset.defaultCharset()))
                        }
                        context.completeNow()
                    }
                }
        )
    }
}
