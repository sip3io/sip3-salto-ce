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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.parser.StringMsgParser
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

class SipOptionsHandlerTest : VertxTest() {

    companion object {

        init {
            StringMsgParser.setComputeContentLengthFromMessage(true)
        }

        val NOW = System.currentTimeMillis()

        val PACKET_1 = Packet().apply {
            timestamp = Timestamp(NOW)
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            attributes[Attributes.caller] = "caller"
            payload = """
                        OPTIONS sip:1010@192.168.10.123:61540;rinstance=afd8f6ae9bf996b2 SIP/2.0
                        Via: SIP/2.0/UDP 192.168.10.5:5060;rport;branch=z9hG4bKPj5464cb3a-5b18-4e5d-97b1-1cbbd70cb879
                        From: <sip:1010@192.168.10.5>;tag=883bc998-e361-4d38-ab78-8a543da457f1
                        To: <sip:1010@192.168.10.123;rinstance=afd8f6ae9bf996b2>
                        Contact: <sip:1010@192.168.10.5:5060>
                        Call-ID: 7061e3c4-d807-4cce-859f-2bef92d9f4e0
                        CSeq: 4358 OPTIONS
                        Max-Forwards: 70
                        User-Agent: FPBX-14.0.13.23(13.29.2)
                        Content-Length:  0

                    """.trimIndent().toByteArray()
        }

        val PACKET_2 = Packet().apply {
            timestamp = Timestamp(NOW + 25)
            srcAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            dstAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            payload = """
                        SIP/2.0 200 OK
                        Via: SIP/2.0/UDP 192.168.10.5:5060;rport=5060;branch=z9hG4bKPj5464cb3a-5b18-4e5d-97b1-1cbbd70cb879
                        Contact: <sip:192.168.10.123:61540>
                        To: <sip:1010@192.168.10.123;rinstance=afd8f6ae9bf996b2>;tag=4a2be263
                        From: <sip:1010@192.168.10.5>;tag=883bc998-e361-4d38-ab78-8a543da457f1
                        Call-ID: 7061e3c4-d807-4cce-859f-2bef92d9f4e0
                        CSeq: 4358 OPTIONS
                        Accept: application/sdp
                        Accept-Language: en
                        Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REGISTER, SUBSCRIBE, NOTIFY, REFER, INFO, MESSAGE
                        Supported: replaces
                        Allow-Events: presence, message-summary, tunnel-info
                        Content-Length: 0

                    """.trimIndent().toByteArray()
        }
    }

    @Test
    fun `Aggregate valid OPTIONS transaction`() {
        val transaction = SipTransaction().apply {
            addPacket(PACKET_1)
            addPacket(PACKET_2)
        }

        runTest(
                deploy = {
                    vertx.deployTestVerticle(SipOptionsHandler::class, config = JsonObject().apply {
                        put("index", 0)
                    })
                },
                execute = {
                    vertx.eventBus().send(SipOptionsHandler.PREFIX + "_0", transaction, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()

                        document = document.getJsonObject("document")
                        context.verify {
                            assertTrue(collection.startsWith("sip_options_index"))
                            assertEquals(NOW, document.getLong("created_at"))
                            assertEquals(NOW + 25, document.getLong("terminated_at"))
                            assertEquals(PACKET_1.srcAddr.addr, document.getString("src_addr"))
                            assertEquals(PACKET_1.srcAddr.port, document.getInteger("src_port"))
                            assertEquals(PACKET_1.dstAddr.addr, document.getString("dst_addr"))
                            assertEquals(PACKET_1.dstAddr.port, document.getInteger("dst_port"))
                            assertEquals("caller", document.getString("caller"))
                            assertEquals(SipTransaction.SUCCEED, document.getString("state"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    private fun SipTransaction.addPacket(packet: Packet) {
        val message = StringMsgParser().parseSIPMessage(packet.payload, true, false, null)
        addMessage(packet, message)
    }
}