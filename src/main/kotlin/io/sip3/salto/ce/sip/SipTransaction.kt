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
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.*

class SipTransaction {

    companion object {

        const val UNKNOWN = "unknown"
        const val TRYING = "trying"
        const val RINGING = "ringing"
        const val FAILED = "failed"
        const val CANCELED = "canceled"
        const val SUCCEED = "succeed"
        const val REDIRECTED = "redirected"
        const val UNAUTHORIZED = "unauthorized"
    }

    lateinit var id: String

    lateinit var cseqMethod: String
    var cseqNumber: Long = 0

    var createdAt: Long = 0
    var originatedAt: Long? = null
    var tryingAt: Long? = null
    var ringingAt: Long? = null
    var terminatedAt: Long? = null

    var expires: Int? = null

    var state = UNKNOWN

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

    fun addMessage(packet: Packet, message: SIPMessage, extend: Boolean = true) {
        // Aggregate transaction data
        when (message) {
            is SIPRequest -> {
                if (createdAt == 0L) {
                    id = message.transactionId()

                    cseqMethod = message.cseqMethod()!!
                    cseqNumber = message.cseqNumber()!!

                    createdAt = packet.createdAt
                    srcAddr = packet.srcAddr
                    dstAddr = packet.dstAddr

                    callId = message.callId()!!
                    callee = message.toUserOrNumber()!!
                    caller = message.fromUserOrNumber()!!
                }

                // Received message is a retransmit
                if (originatedAt != null) {
                    attributes[Attributes.retransmits] = true
                } else {
                    originatedAt = packet.createdAt
                    if (extend) request = message
                }

                if (cseqMethod == "REGISTER" && expires == null) expires = message.expires()
            }
            is SIPResponse -> {
                if (createdAt == 0L) {
                    id = message.transactionId()

                    cseqMethod = message.cseqMethod()!!
                    cseqNumber = message.cseqNumber()!!

                    createdAt = packet.createdAt
                    srcAddr = packet.dstAddr
                    dstAddr = packet.srcAddr

                    callId = message.callId()!!
                    callee = message.toUserOrNumber()!!
                    caller = message.fromUserOrNumber()!!
                }

                val statusCode = message.statusCode
                when (statusCode) {
                    100 -> {
                        if (tryingAt == null) {
                            if (extend) response = message
                            tryingAt = packet.createdAt
                            if (state == UNKNOWN) state = TRYING
                        }
                    }
                    in 180..183 -> {
                        if (ringingAt == null) {
                            ringingAt = packet.createdAt
                            if (terminatedAt == null) {
                                if (extend) response = message
                                if (state == UNKNOWN || state == TRYING) state = RINGING
                            }
                        }
                    }
                    200 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt
                            state = SUCCEED

                            if (cseqMethod == "REGISTER") {
                                message.expires()?.let { expires = it }
                            }
                        }
                    }
                    in 300..399 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt
                            state = REDIRECTED
                        }
                    }
                    401, 407 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt
                            state = UNAUTHORIZED
                        }
                    }
                    487 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt
                            state = CANCELED
                        }
                    }
                    in 400..499 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt

                            attributes[Attributes.error_code] = statusCode.toString()
                            attributes[Attributes.error_type] = "client"
                            state = FAILED
                        }
                    }
                    in 500..599 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt

                            attributes[Attributes.error_code] = statusCode.toString()
                            attributes[Attributes.error_type] = "server"
                            state = FAILED
                        }
                    }
                    in 600..699 -> {
                        // Received message is a retransmit
                        if (response?.statusCode == statusCode) {
                            attributes[Attributes.retransmits] = true
                        } else {
                            if (extend) response = message
                            terminatedAt = packet.createdAt

                            attributes[Attributes.error_code] = statusCode.toString()
                            attributes[Attributes.error_type] = "global"
                            state = FAILED
                        }
                    }
                }
            }
        }

        // Copy attributes
        packet.attributes.forEach { (name, value) -> attributes[name] = value }
    }
}