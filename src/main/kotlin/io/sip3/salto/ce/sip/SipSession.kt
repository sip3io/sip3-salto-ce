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

import io.sip3.salto.ce.domain.Address

class SipSession {

    companion object {

        const val UNKNOWN = "unknown"
        const val FAILED = "failed"
        const val CANCELED = "canceled"
        const val ANSWERED = "answered"
        const val REDIRECTED = "redirected"
        const val REGISTERED = "registered"
        const val UNAUTHORIZED = "unauthorized"
    }

    var state = UNKNOWN

    var createdAt: Long = 0
    var terminatedAt: Long? = null

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    lateinit var caller: String
    lateinit var callee: String
    lateinit var callId: String

    var duration: Long? = null
    var setupTime: Long? = null

    var attributes = mutableMapOf<String, Any>()

    fun addInviteTransaction(transaction: SipTransaction) {
        // TODO...
    }

    fun addByeTransaction(transaction: SipTransaction) {
        // TODO...
    }
}