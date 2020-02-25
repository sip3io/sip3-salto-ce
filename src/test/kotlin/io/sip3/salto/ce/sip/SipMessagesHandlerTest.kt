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

class SipMessagesHandlerTest : VertxTest() {

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
                        MESSAGE sip:user2@domain.com SIP/2.0
                        Via: SIP/2.0/TCP user1pc.domain.com;branch=z9hG4bK776sgdkse
                        Max-Forwards: 70
                        From: sip:user1@domain.com;tag=49583
                        To: sip:user2@domain.com
                        Call-ID: asd88asd77a@1.2.3.4
                        CSeq: 1 MESSAGE
                        Content-Type: text/plain
                        Content-Length: 18
                        
                        Watson, come here.
                        
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
                        Via: SIP/2.0/TCP user1pc.domain.com;branch=z9hG4bK776sgdkse;received=1.2.3.4
                        From: sip:user1@domain.com;;tag=49394
                        To: sip:user2@domain.com;tag=ab8asdasd9
                        Call-ID: asd88asd77a@1.2.3.4
                        CSeq: 1 MESSAGE
                        Content-Length: 0

                    """.trimIndent().toByteArray()
        }
    }

    @Test
    fun `Aggregate valid MESSAGE transaction`() {
        val transaction = SipTransaction().apply {
            addPacket(PACKET_1)
            addPacket(PACKET_2)
        }

        runTest(
                deploy = {
                    vertx.deployTestVerticle(SipMessagesHandler::class, config = JsonObject().apply {
                        put("index", 0)
                    })
                },
                execute = {
                    vertx.eventBus().send(SipMessagesHandler.PREFIX + "_0", transaction, USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()

                        document = document.getJsonObject("document")
                        context.verify {
                            assertTrue(collection.startsWith("sip_message_index"))
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