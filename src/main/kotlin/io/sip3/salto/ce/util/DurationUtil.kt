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

import java.time.Duration

object DurationUtil {

    fun parseDuration(duration: String): Duration {
        return when {
            duration.endsWith("ms") -> Duration.ofMillis(duration.substringBefore("ms").toLong())
            duration.endsWith("s") -> Duration.ofSeconds(duration.substringBefore("s").toLong())
            duration.endsWith("m") -> Duration.ofMinutes(duration.substringBefore("m").toLong())
            duration.endsWith("h") -> Duration.ofHours(duration.substringBefore("h").toLong())
            else -> throw IllegalArgumentException("Unsupported time format: $duration")
        }
    }
}