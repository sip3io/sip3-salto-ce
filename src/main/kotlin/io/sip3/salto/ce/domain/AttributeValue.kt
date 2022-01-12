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

package io.sip3.salto.ce.domain

data class AttributeValue(
    val name: String,
    val value: Any
) {
    val mode: Mode = Mode.parse(name)

    class Mode(
        val db: Boolean = true,
        val options: Boolean = true,
        val metrics: Boolean = true
    ) {

        companion object {

            private const val MODE_WRITE_DB = "d"
            private const val MODE_WRITE_OPTIONS = "o"
            private const val MODE_WRITE_METRICS = "m"

            private val DEFAULT = Mode()
            private val PROCESSING_ONLY = Mode(db = false, options = false, metrics = false)

            fun parse(name: String): Mode {
                if (!name.contains(":")) return DEFAULT

                val raw = name.substringBefore(":").lowercase()
                if (raw.isBlank()) return PROCESSING_ONLY

                return Mode(
                    raw.contains(MODE_WRITE_DB),
                    raw.contains(MODE_WRITE_OPTIONS),
                    raw.contains(MODE_WRITE_METRICS)
                )
            }
        }
    }
}