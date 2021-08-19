/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

import java.sql.Timestamp

class Packet {

    lateinit var timestamp: Timestamp

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    lateinit var source: String

    var protocolCode: Byte = 0
    lateinit var payload: ByteArray

    var attributes = mutableMapOf<String, Any>()

    val createdAt: Long by lazy {
        timestamp.time
    }

    override fun toString(): String {
        return "Packet(timestamp=$timestamp, srcAddr=$srcAddr, dstAddr=$dstAddr, source=$source, protocolCode=$protocolCode, payload=${payload.contentToString()}, attributes=$attributes)"
    }
}