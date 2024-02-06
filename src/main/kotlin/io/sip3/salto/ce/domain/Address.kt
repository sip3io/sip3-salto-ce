/*
 * Copyright 2018-2024 SIP3.IO, Corp.
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

package io.sip3.salto.ce.domain

import io.sip3.commons.util.MediaUtil

class Address {

    lateinit var addr: String
    var port: Int = 0
    var host: String? = null

    fun compositeKey(other: Address, keyMapping: (Address) -> String): String {
        val thisKey = keyMapping.invoke(this)
        val otherKey = keyMapping.invoke(other)

        return if (thisKey > otherKey) {
            "$thisKey:$otherKey"
        } else {
            "$otherKey:$thisKey"
        }
    }

    fun compositeKey(other: Address): String {
        return compositeKey(other) { it.host ?: it.addr }
    }

    fun sdpSessionId(): String {
        return MediaUtil.sdpSessionId(addr, port)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Address) return false

        return addr == other.addr && port == other.port
    }

    override fun hashCode(): Int {
        var result = addr.hashCode()
        result = 31 * result + port
        return result
    }

    override fun toString(): String {
        return "Address(addr='$addr', port=$port, host=$host)"
    }
}