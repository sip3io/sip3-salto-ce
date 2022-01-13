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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttributeUtilTest {

    @Test
    fun `Validate mode parsing`() {
        val attributes = mutableMapOf(
            "uda_without_prefix" to "with_options",
            ":uda_empty_prefix" to true,
            "d:uda_database_only" to "no_options",
            "o:uda_options_only" to "with_options",
            "m:uda_metrics_only" to "no_options",
            "do:uda_database_with_options" to "with_options",
            "dm:uda_all_without_options" to "no_options",
            "om:uda_options_with_metrics" to "with_options",
            "dom:uda_all_modes" to true
        )

        val databaseAttributes = attributes.toDatabaseAttributes()
        assertEquals(5, databaseAttributes.size)
        assertTrue(databaseAttributes.keys.none { it.contains(":") })
        assertFalse(databaseAttributes.contains("uda_empty_prefix"))
        assertEquals("", databaseAttributes["uda_database_only"])
        assertEquals("", databaseAttributes["uda_all_without_options"])
        assertEquals("with_options", databaseAttributes["uda_database_with_options"])
        assertEquals("with_options", databaseAttributes["uda_without_prefix"])

        val metricsAttributes = attributes.toMetricsAttributes()
        assertEquals(5, metricsAttributes.size)
        assertTrue(metricsAttributes.keys.none { it.contains(":") })

    }
}