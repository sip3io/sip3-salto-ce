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

package io.sip3.salto.ce.sip

import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.domain.Address

class SipSession {

    companion object {

        const val UNKNOWN = "unknown"
        const val FAILED = "failed"
        const val CANCELED = "canceled"
        const val ANSWERED = "answered"
        const val REDIRECTED = "redirected"
        const val UNAUTHORIZED = "unauthorized"
    }

    var state = UNKNOWN

    var createdAt: Long = 0
    var answeredAt: Long? = null
    var terminatedAt: Long? = null

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    lateinit var callId: String
    lateinit var callee: String
    lateinit var caller: String

    var duration: Long? = null
    var setupTime: Long? = null

    var attributes = mutableMapOf<String, Any>()

    fun addInviteTransaction(transaction: SipTransaction) {
        if (createdAt == 0L) {
            createdAt = transaction.createdAt
            srcAddr = transaction.srcAddr
            dstAddr = transaction.dstAddr
            callId = transaction.callId
            callee = transaction.callee
            caller = transaction.caller
        }

        val statusCode = transaction.response?.statusCode
        if (statusCode != null && state != ANSWERED) {
            when (statusCode) {
                200 -> {
                    state = ANSWERED
                    answeredAt = transaction.terminatedAt ?: transaction.createdAt
                    transaction.ringingAt?.let { ringingAt ->
                        setupTime = ringingAt - transaction.createdAt
                    }
                }
                in 300..399 -> {
                    state = REDIRECTED
                    terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                }
                401, 407 -> {
                    state = UNAUTHORIZED
                }
                487 -> {
                    state = CANCELED
                    terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                }
                in 400..699 -> {
                    state = FAILED
                    terminatedAt = transaction.terminatedAt ?: transaction.createdAt
                    attributes[Attributes.error_code] = statusCode
                }
            }
        }

        transaction.attributes.forEach { (name, value) -> attributes[name] = value }
    }

    fun addByeTransaction(transaction: SipTransaction) {
        if (terminatedAt == null) {
            terminatedAt = transaction.terminatedAt ?: transaction.createdAt

            answeredAt?.let { answeredAt ->
                duration = transaction.createdAt - answeredAt
            }

            transaction.attributes.forEach { (name, value) -> attributes[name] = value }
        }
    }
}