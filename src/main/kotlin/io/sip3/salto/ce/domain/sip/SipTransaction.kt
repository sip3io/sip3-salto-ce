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

package io.sip3.salto.ce.domain.sip

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet

abstract class SipTransaction {

    var createdAt = System.currentTimeMillis()

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    var request: SIPRequest? = null
    var response: SIPResponse? = null

    var attributes = mutableMapOf<String, Any>()

    abstract fun addSipRequest(packet: Packet, message: SIPMessage)

    abstract fun addSipResponse(packet: Packet, message: SIPMessage)

    fun addMessage(packet: Packet, message: SIPMessage) {
        // Copy attributes
        packet.attributes.forEach { (name, value) ->
            attributes[name] = value
        }

        // Aggregate transaction data
        when (message) {
            is SIPRequest -> {
                if (request == null) {
                    srcAddr = packet.srcAddr
                    dstAddr = packet.dstAddr
                }
                addSipRequest(packet, message)
            }
            is SIPResponse -> {
                if (response == null) {
                    srcAddr = packet.dstAddr
                    dstAddr = packet.srcAddr
                }
                addSipResponse(packet, message)
            }
        }
    }

    fun isReady(): Boolean {
        return request != null && response != null
    }
}