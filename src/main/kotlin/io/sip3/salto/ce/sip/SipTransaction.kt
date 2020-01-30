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

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.callId
import io.sip3.salto.ce.util.fromUserOrNumber
import io.sip3.salto.ce.util.toUserOrNumber

class SipTransaction {

    var createdAt: Long = 0
    var tryingAt: Long? = null
    var ringingAt: Long? = null
    var terminatedAt: Long? = null

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    lateinit var callId: String
    lateinit var callee: String
    lateinit var caller: String

    var request: SIPRequest? = null
    var response: SIPResponse? = null

    var attributes = mutableMapOf<String, Any>()

    val legId: String by lazy {
        srcAddr.compositeKey(dstAddr)
    }

    fun addMessage(packet: Packet, message: SIPMessage) {
        // Aggregate transaction data
        when (message) {
            is SIPRequest -> {
                if (createdAt == 0L) {
                    createdAt = packet.createdAt
                    srcAddr = packet.srcAddr
                    dstAddr = packet.dstAddr
                    callId = message.callId()!!
                    callee = message.toUserOrNumber()!!
                    caller = message.fromUserOrNumber()!!
                }

                // Received message is a retransmit
                if (request != null) {
                    attributes["retransmits"] = true
                } else {
                    request = message
                }
            }
            is SIPResponse -> {
                if (createdAt == 0L) {
                    createdAt = packet.createdAt
                    srcAddr = packet.dstAddr
                    dstAddr = packet.srcAddr
                    callId = message.callId()!!
                    callee = message.toUserOrNumber()!!
                    caller = message.fromUserOrNumber()!!
                }

                when (message.statusCode) {
                    100 -> if (tryingAt == null) tryingAt = packet.createdAt
                    in 180..183 -> if (ringingAt == null) ringingAt = packet.createdAt
                    in 200..699 -> {
                        // Received message is a retransmit
                        if (response != null) {
                            attributes["retransmits"] = true
                        } else {
                            response = message
                            terminatedAt = packet.createdAt
                        }
                    }
                }
            }
        }

        // Copy attributes
        packet.attributes.forEach { (name, value) -> attributes[name] = value }
    }
}