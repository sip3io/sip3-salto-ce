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

package io.sip3.salto.ce.util

object MediaUtil {

    const val R0 = 93.2F
    const val MOS_MIN = 1F
    const val MOS_MAX = 4.5F

    fun computeMos(rFactor: Float): Float {
        return when {
            rFactor < 0 -> MOS_MIN
            rFactor > 100F -> MOS_MAX
            else -> (1 + rFactor * 0.035 + rFactor * (100 - rFactor) * (rFactor - 60) * 0.000007).toFloat()
        }
    }
}