/*
 * Copyright 2018-2022 SIP3.IO, Corp.
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

import io.sip3.salto.ce.util.DurationUtil.parseDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class DurationUtilTest {

    @Test
    fun `Check parseDuration() method`() {
        assertEquals(Duration.ofMillis(100), parseDuration("100ms"))
        assertEquals(Duration.ofSeconds(10), parseDuration("10s"))
        assertEquals(Duration.ofMinutes(5), parseDuration("5m"))
        assertEquals(Duration.ofHours(1), parseDuration("1h"))
        assertThrows<IllegalArgumentException> { parseDuration("1hour") }
    }
}