/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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

package io.sip3.salto.ce.util

import io.sip3.commons.domain.media.MediaAddress
import io.sip3.salto.ce.domain.Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaAddressUtilTest {

    companion object {

        val MEDIA_ADDRESS = MediaAddress().apply {
            addr = "192.168.168.100"
            rtpPort = 12002
            rtcpPort = 12003
        }
    }

    @Test
    fun `Check 'rtpAddress()' method`() {
        val addr = Address().apply {
            addr = MEDIA_ADDRESS.addr
            port = MEDIA_ADDRESS.rtpPort
        }

        assertEquals(addr, MEDIA_ADDRESS.rtpAddress())
    }

    @Test
    fun `Check 'rtcpAddress()' method`() {
        val addr = Address().apply {
            addr = MEDIA_ADDRESS.addr
            port = MEDIA_ADDRESS.rtcpPort
        }

        assertEquals(addr, MEDIA_ADDRESS.rtcpAddress())
    }
}