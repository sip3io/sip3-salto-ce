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

class HepDecoderTest : VertxTest() {

    companion object {

        // Payload: HEPv2
        val PACKET_1 = byteArrayOf(
            0x02.toByte(), 0x10.toByte(), 0x02.toByte(), 0x11.toByte(), 0x13.toByte(), 0xc4.toByte(), 0x13.toByte(),
            0xc6.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x0a.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x04.toByte(), 0x2e.toByte(), 0x6f.toByte(), 0xa7.toByte(), 0x5d.toByte(), 0x94.toByte(),
            0xad.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(), 0x30.toByte()
        )

        // Payload: HEPv3
        val PACKET_2 = byteArrayOf(
            0x48.toByte(), 0x45.toByte(), 0x50.toByte(), 0x33.toByte(), 0x02.toByte(), 0x20.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x07.toByte(), 0x02.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x11.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x16.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0xff.toByte(), 0xff.toByte(), 0x4f.toByte(), 0x8e.toByte(), 0x56.toByte(), 0x76.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x16.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xff.toByte(), 0xff.toByte(), 0xd4.toByte(), 0x30.toByte(), 0xc5.toByte(),
            0x96.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x07.toByte(), 0x00.toByte(), 0x08.toByte(),
            0x13.toByte(), 0xc7.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte(), 0x00.toByte(),
            0x08.toByte(), 0x13.toByte(), 0xc4.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x09.toByte(),
            0x00.toByte(), 0x0a.toByte(), 0x5c.toByte(), 0x6a.toByte(), 0xa4.toByte(), 0x58.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x04.toByte(),
            0x06.toByte(), 0x5e.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(),
            0x07.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0c.toByte(), 0x00.toByte(),
            0x0a.toByte(), 0x00.toByte(), 0x00.toByte(), 0x07.toByte(), 0xd4.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x0e.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x6d.toByte(), 0x79.toByte(), 0x68.toByte(),
            0x65.toByte(), 0x70.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0f.toByte(), 0x00.toByte(),
            0x0c.toByte(), 0x4e.toByte(), 0x4f.toByte(), 0x54.toByte(), 0x49.toByte(), 0x46.toByte(), 0x59.toByte()
        )

        //Payload: HEPv3 RTCP report
        val PACKET_3 = byteArrayOf(
            0x48.toByte(), 0x45.toByte(), 0x50.toByte(), 0x33.toByte(), 0x02.toByte(), 0xb7.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x07.toByte(), 0x02.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x00.toByte(), 0x07.toByte(), 0x11.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x0a.toByte(), 0xc0.toByte(), 0xa8.toByte(),
            0x09.toByte(), 0x77.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x0a.toByte(), 0xc0.toByte(), 0xa8.toByte(), 0x04.toByte(), 0x8c.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x07.toByte(), 0x00.toByte(), 0x08.toByte(), 0x46.toByte(), 0x31.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x08.toByte(), 0x00.toByte(), 0x08.toByte(), 0x13.toByte(), 0x8d.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x09.toByte(), 0x00.toByte(), 0x0a.toByte(), 0x5f.toByte(),
            0x86.toByte(), 0x9c.toByte(), 0xc8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0a.toByte(),
            0x00.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x1e.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x07.toByte(), 0x05.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0c.toByte(), 0x00.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xd2.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0f.toByte(), 0x02.toByte(),
            0x28.toByte(), 0x7b.toByte(), 0x22.toByte(), 0x73.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x64.toByte(),
            0x65.toByte(), 0x72.toByte(), 0x5f.toByte(), 0x69.toByte(), 0x6e.toByte(), 0x66.toByte(), 0x6f.toByte(),
            0x72.toByte(), 0x6d.toByte(), 0x61.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x7b.toByte(), 0x22.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x70.toByte(),
            0x5f.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x73.toByte(), 0x74.toByte(),
            0x61.toByte(), 0x6d.toByte(), 0x70.toByte(), 0x5f.toByte(), 0x73.toByte(), 0x65.toByte(), 0x63.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x33.toByte(), 0x38.toByte(), 0x31.toByte(), 0x31.toByte(), 0x36.toByte(),
            0x34.toByte(), 0x36.toByte(), 0x32.toByte(), 0x38.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(),
            0x6e.toByte(), 0x74.toByte(), 0x70.toByte(), 0x5f.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(),
            0x65.toByte(), 0x73.toByte(), 0x74.toByte(), 0x61.toByte(), 0x6d.toByte(), 0x70.toByte(), 0x5f.toByte(),
            0x75.toByte(), 0x73.toByte(), 0x65.toByte(), 0x63.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x32.toByte(),
            0x31.toByte(), 0x30.toByte(), 0x30.toByte(), 0x37.toByte(), 0x31.toByte(), 0x31.toByte(), 0x34.toByte(),
            0x35.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x72.toByte(), 0x74.toByte(), 0x70.toByte(), 0x5f.toByte(),
            0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x73.toByte(), 0x74.toByte(), 0x61.toByte(),
            0x6d.toByte(), 0x70.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x31.toByte(), 0x36.toByte(), 0x31.toByte(),
            0x30.toByte(), 0x38.toByte(), 0x32.toByte(), 0x34.toByte(), 0x31.toByte(), 0x36.toByte(), 0x38.toByte(),
            0x2c.toByte(), 0x22.toByte(), 0x70.toByte(), 0x61.toByte(), 0x63.toByte(), 0x6b.toByte(), 0x65.toByte(),
            0x74.toByte(), 0x73.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x35.toByte(), 0x39.toByte(), 0x35.toByte(),
            0x2c.toByte(), 0x22.toByte(), 0x6f.toByte(), 0x63.toByte(), 0x74.toByte(), 0x65.toByte(), 0x74.toByte(),
            0x73.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x39.toByte(), 0x35.toByte(), 0x32.toByte(), 0x30.toByte(),
            0x30.toByte(), 0x7d.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x73.toByte(), 0x73.toByte(), 0x72.toByte(),
            0x63.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x32.toByte(), 0x31.toByte(), 0x34.toByte(), 0x31.toByte(),
            0x36.toByte(), 0x36.toByte(), 0x30.toByte(), 0x37.toByte(), 0x30.toByte(), 0x30.toByte(), 0x2c.toByte(),
            0x22.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x22.toByte(), 0x3a.toByte(),
            0x32.toByte(), 0x30.toByte(), 0x32.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x72.toByte(), 0x65.toByte(),
            0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(), 0x5f.toByte(), 0x63.toByte(), 0x6f.toByte(),
            0x75.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x31.toByte(), 0x2c.toByte(),
            0x22.toByte(), 0x72.toByte(), 0x65.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(),
            0x5f.toByte(), 0x62.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x63.toByte(), 0x6b.toByte(), 0x73.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x5b.toByte(), 0x7b.toByte(), 0x22.toByte(), 0x73.toByte(), 0x6f.toByte(),
            0x75.toByte(), 0x72.toByte(), 0x63.toByte(), 0x65.toByte(), 0x5f.toByte(), 0x73.toByte(), 0x73.toByte(),
            0x72.toByte(), 0x63.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x32.toByte(), 0x39.toByte(), 0x39.toByte(),
            0x38.toByte(), 0x31.toByte(), 0x36.toByte(), 0x36.toByte(), 0x30.toByte(), 0x31.toByte(), 0x2c.toByte(),
            0x22.toByte(), 0x66.toByte(), 0x72.toByte(), 0x61.toByte(), 0x63.toByte(), 0x74.toByte(), 0x69.toByte(),
            0x6f.toByte(), 0x6e.toByte(), 0x5f.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x73.toByte(), 0x74.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x70.toByte(), 0x61.toByte(),
            0x63.toByte(), 0x6b.toByte(), 0x65.toByte(), 0x74.toByte(), 0x73.toByte(), 0x5f.toByte(), 0x6c.toByte(),
            0x6f.toByte(), 0x73.toByte(), 0x74.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x31.toByte(), 0x2c.toByte(),
            0x22.toByte(), 0x68.toByte(), 0x69.toByte(), 0x67.toByte(), 0x68.toByte(), 0x65.toByte(), 0x73.toByte(),
            0x74.toByte(), 0x5f.toByte(), 0x73.toByte(), 0x65.toByte(), 0x71.toByte(), 0x5f.toByte(), 0x6e.toByte(),
            0x6f.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x33.toByte(), 0x34.toByte(), 0x33.toByte(), 0x31.toByte(),
            0x32.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x69.toByte(), 0x61.toByte(), 0x5f.toByte(), 0x6a.toByte(),
            0x69.toByte(), 0x74.toByte(), 0x74.toByte(), 0x65.toByte(), 0x72.toByte(), 0x22.toByte(), 0x3a.toByte(),
            0x34.toByte(), 0x32.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x6c.toByte(), 0x73.toByte(), 0x72.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x34.toByte(), 0x35.toByte(), 0x37.toByte(), 0x35.toByte(), 0x37.toByte(),
            0x33.toByte(), 0x35.toByte(), 0x39.toByte(), 0x37.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x64.toByte(),
            0x6c.toByte(), 0x73.toByte(), 0x72.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x31.toByte(), 0x32.toByte(),
            0x38.toByte(), 0x34.toByte(), 0x35.toByte(), 0x31.toByte(), 0x7d.toByte(), 0x5d.toByte(), 0x2c.toByte(),
            0x22.toByte(), 0x72.toByte(), 0x65.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(),
            0x5f.toByte(), 0x62.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x63.toByte(), 0x6b.toByte(), 0x73.toByte(),
            0x5f.toByte(), 0x78.toByte(), 0x72.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x7b.toByte(), 0x22.toByte(),
            0x74.toByte(), 0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(),
            0x2c.toByte(), 0x22.toByte(), 0x69.toByte(), 0x64.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(),
            0x2c.toByte(), 0x22.toByte(), 0x66.toByte(), 0x72.toByte(), 0x61.toByte(), 0x63.toByte(), 0x74.toByte(),
            0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x5f.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x73.toByte(),
            0x74.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x66.toByte(),
            0x72.toByte(), 0x61.toByte(), 0x63.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
            0x5f.toByte(), 0x64.toByte(), 0x69.toByte(), 0x73.toByte(), 0x63.toByte(), 0x61.toByte(), 0x72.toByte(),
            0x64.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x62.toByte(),
            0x75.toByte(), 0x72.toByte(), 0x73.toByte(), 0x74.toByte(), 0x5f.toByte(), 0x64.toByte(), 0x65.toByte(),
            0x6e.toByte(), 0x73.toByte(), 0x69.toByte(), 0x74.toByte(), 0x79.toByte(), 0x22.toByte(), 0x3a.toByte(),
            0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x67.toByte(), 0x61.toByte(), 0x70.toByte(), 0x5f.toByte(),
            0x64.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x73.toByte(), 0x69.toByte(), 0x74.toByte(), 0x79.toByte(),
            0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x62.toByte(), 0x75.toByte(),
            0x72.toByte(), 0x73.toByte(), 0x74.toByte(), 0x5f.toByte(), 0x64.toByte(), 0x75.toByte(), 0x72.toByte(),
            0x61.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x22.toByte(), 0x3a.toByte(),
            0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x67.toByte(), 0x61.toByte(), 0x70.toByte(), 0x5f.toByte(),
            0x64.toByte(), 0x75.toByte(), 0x72.toByte(), 0x61.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(),
            0x6e.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x72.toByte(),
            0x6f.toByte(), 0x75.toByte(), 0x6e.toByte(), 0x64.toByte(), 0x5f.toByte(), 0x74.toByte(), 0x72.toByte(),
            0x69.toByte(), 0x70.toByte(), 0x5f.toByte(), 0x64.toByte(), 0x65.toByte(), 0x6c.toByte(), 0x61.toByte(),
            0x79.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x2c.toByte(), 0x22.toByte(), 0x65.toByte(),
            0x6e.toByte(), 0x64.toByte(), 0x5f.toByte(), 0x73.toByte(), 0x79.toByte(), 0x73.toByte(), 0x74.toByte(),
            0x65.toByte(), 0x6d.toByte(), 0x5f.toByte(), 0x64.toByte(), 0x65.toByte(), 0x6c.toByte(), 0x61.toByte(),
            0x79.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x30.toByte(), 0x7d.toByte(), 0x2c.toByte(), 0x22.toByte(),
            0x73.toByte(), 0x64.toByte(), 0x65.toByte(), 0x73.toByte(), 0x5f.toByte(), 0x73.toByte(), 0x73.toByte(),
            0x72.toByte(), 0x63.toByte(), 0x22.toByte(), 0x3a.toByte(), 0x32.toByte(), 0x31.toByte(), 0x34.toByte(),
            0x31.toByte(), 0x36.toByte(), 0x36.toByte(), 0x30.toByte(), 0x37.toByte(), 0x30.toByte(), 0x30.toByte(),
            0x7d.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x11.toByte(), 0x00.toByte(), 0x2a.toByte(),
            0x39.toByte(), 0x34.toByte(), 0x36.toByte(), 0x62.toByte(), 0x35.toByte(), 0x62.toByte(), 0x33.toByte(),
            0x63.toByte(), 0x2d.toByte(), 0x38.toByte(), 0x38.toByte(), 0x38.toByte(), 0x61.toByte(), 0x2d.toByte(),
            0x31.toByte(), 0x32.toByte(), 0x33.toByte(), 0x39.toByte(), 0x2d.toByte(), 0x38.toByte(), 0x38.toByte(),
            0x38.toByte(), 0x31.toByte(), 0x2d.toByte(), 0x30.toByte(), 0x30.toByte(), 0x31.toByte(), 0x35.toByte(),
            0x35.toByte(), 0x64.toByte(), 0x30.toByte(), 0x39.toByte(), 0x63.toByte(), 0x34.toByte(), 0x30.toByte(),
            0x31.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x12.toByte(), 0x00.toByte(), 0x08.toByte(),
            0x00.toByte(), 0x00.toByte()
        )
    }

    @Test
    fun `Decode HEPv2 SIP packet`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(HepDecoder::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.hep2, Pair(sender, Buffer.buffer(PACKET_1)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
                    val (_, packet) = event.body()
                    context.verify {
                        assertEquals(1571254062044, packet.timestamp.time)
                        assertEquals(44000436, packet.timestamp.nanos)
                        val src = packet.srcAddr
                        assertEquals("10.0.0.4", src.addr)
                        assertEquals(5060, src.port)
                        val dst = packet.dstAddr
                        assertEquals("10.0.0.4", dst.addr)
                        assertEquals(5062, dst.port)
                        assertEquals("SIP/2.0", packet.payload.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Decode HEPv3 SIP packet`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(HepDecoder::class)
            },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.hep3, Pair(sender, Buffer.buffer(PACKET_2)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
                    val (_, packet) = event.body()
                    context.verify {
                        assertEquals("hep3", packet.source)
                        assertEquals(PacketTypes.SIP, packet.protocolCode)
                        assertEquals(1550492760263, packet.timestamp.time)
                        assertEquals(263000774, packet.timestamp.nanos)
                        val src = packet.srcAddr
                        assertEquals("79.142.86.118", src.addr)
                        assertEquals(5063, src.port)
                        val dst = packet.dstAddr
                        assertEquals("212.48.197.150", dst.addr)
                        assertEquals(5060, dst.port)
                        assertEquals("NOTIFY", packet.payload.toString(Charset.defaultCharset()))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Decode HEPv3 RTCP packet`() {
        runTest(
            deploy = { vertx.deployTestVerticle(HepDecoder::class) },
            execute = {
                val sender = Address().apply {
                    addr = "127.0.0.1"
                    port = 5060
                }
                vertx.eventBus().localSend(RoutesCE.hep3, Pair(sender, Buffer.buffer(PACKET_3)))
            },
            assert = {
                vertx.eventBus().consumer<Pair<Address, Packet>>(RoutesCE.router) { event ->
                    val (_, packet) = event.body()
                    context.verify {
                        assertEquals(546, packet.payload.size)
                        assertEquals(1602657480066, packet.timestamp.time)
                        assertEquals(66000334, packet.timestamp.nanos)
                        assertEquals(PacketTypes.RTCP, packet.protocolCode)
                        val src = packet.srcAddr
                        assertEquals("192.168.9.119", src.addr)
                        assertEquals(17969, src.port)
                        val dst = packet.dstAddr
                        assertEquals("192.168.4.140", dst.addr)
                        assertEquals(5005, dst.port)
                        assertEquals("hep3", packet.source)
                    }
                    context.completeNow()
                }
            }
        )
    }
}