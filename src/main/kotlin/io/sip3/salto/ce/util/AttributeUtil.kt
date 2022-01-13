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

fun Map<String, Any>.toDatabaseAttributes(): MutableMap<String, Any> {
    val attributes = mutableMapOf<String, Any>()

    forEach { (k, v) ->
        if (AttributeUtil.modeDatabase(k)) {
            attributes[k.substringAfter(":")] = if (v is String && !AttributeUtil.modeOptions(k)) "" else v
        }
    }

    return attributes
}

fun Map<String, Any>.toMetricsAttributes(): MutableMap<String, Any> {
    val attributes = mutableMapOf<String, Any>()

    forEach { (k, v) ->
        if (AttributeUtil.modeMetrics(k)) {
            attributes[k.substringAfter(":")] = v
        }
    }

    return attributes
}

private object AttributeUtil {

    private const val MODE_DATABASE = "d"
    private const val MODE_OPTIONS = "o"
    private const val MODE_METRICS = "m"

    fun modeDatabase(name: String): Boolean {
        return hasMode(name, MODE_DATABASE)
    }

    fun modeOptions(name: String): Boolean {
        return hasMode(name, MODE_OPTIONS)
    }

    fun modeMetrics(name: String): Boolean {
        return hasMode(name, MODE_METRICS)
    }

    private fun hasMode(name: String, mode: String): Boolean {
        val delimiterIndex = name.indexOf(':')
        return delimiterIndex < 0 || name.indexOf(mode, ignoreCase = true) in 0..delimiterIndex
    }
}