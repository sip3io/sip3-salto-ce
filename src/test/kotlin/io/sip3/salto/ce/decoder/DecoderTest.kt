/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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
import io.sip3.salto.ce.Routes
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.buffer.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class DecoderTest : VertxTest() {

    companion object {

        // Payload: SIP3
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
    }

    @Test
    fun `Decode SIP3 SIP packet`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(Decoder::class)
                },
                execute = {
                    vertx.eventBus().send(Routes.sip3, Buffer.buffer(PACKET_1), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Packet>(Routes.router) { event ->
                        val packet = event.body()
                        context.verify {
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