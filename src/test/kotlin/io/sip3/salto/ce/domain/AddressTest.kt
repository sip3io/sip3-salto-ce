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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddressTest {

    @Test
    fun `Check 'compositeKey()' method`() {
        val srcAddr = Address().apply {
            addr = "29.11.19.88"
            port = 5060
        }
        var dstAddr = Address().apply {
            addr = "23.08.20.15"
            port = 5061
        }
        assertEquals("29.11.19.88:23.08.20.15", srcAddr.compositeKey(dstAddr))
        assertEquals("29.11.19.88:23.08.20.15", dstAddr.compositeKey(srcAddr))
        assertEquals("5061:5060", srcAddr.compositeKey(dstAddr) { it.port.toString() })
        assertEquals("5061:5060", dstAddr.compositeKey(srcAddr) { it.port.toString() })

        dstAddr = Address().apply {
            addr = "23.08.20.15"
            host = "Test"
            port = 5061
        }
        assertEquals("Test:29.11.19.88", srcAddr.compositeKey(dstAddr))
        assertEquals("Test:29.11.19.88", dstAddr.compositeKey(srcAddr))
        assertEquals("5061:5060", srcAddr.compositeKey(dstAddr) { it.port.toString() })
        assertEquals("5061:5060", dstAddr.compositeKey(srcAddr) { it.port.toString() })
    }
}