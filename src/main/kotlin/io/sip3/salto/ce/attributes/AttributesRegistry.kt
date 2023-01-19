/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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

package io.sip3.salto.ce.attributes

import io.sip3.commons.domain.Attribute
import io.sip3.commons.util.format
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.time.format.DateTimeFormatter

/**
 * Handles attributes
 */
class AttributesRegistry(val vertx: Vertx, config: JsonObject) {

    companion object {

        const val PREFIX = "attributes"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var checkPeriod: Long = 5000

    private var currentTimeSuffix: String

    private val registry = mutableMapOf<String, Attribute>()

    init {
        config.getString("time_suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config.getJsonObject("attributes")?.getLong("check_period")?.let {
            checkPeriod = it
        }

        currentTimeSuffix = timeSuffix.format(System.currentTimeMillis())
        vertx.setPeriodic(checkPeriod) {
            val newTimeSuffix = timeSuffix.format(System.currentTimeMillis())
            if (currentTimeSuffix < newTimeSuffix) {
                currentTimeSuffix = newTimeSuffix
                registry.clear()
            }
        }
    }

    fun handle(prefix: String, attributes: Map<String, Any>) {
        attributes.forEach { (key, value) ->
            handle(prefix, key, value)
        }
    }

    fun handle(prefix: String, key: String, value: Any) {
        val name = "$prefix.$key"
        val type = when (value) {
            is String -> Attribute.TYPE_STRING
            is Number -> Attribute.TYPE_NUMBER
            is Boolean -> Attribute.TYPE_BOOLEAN
            else -> return
        }

        var attribute = registry[name]
        if (attribute == null) {
            attribute = Attribute().apply {
                this.name = name
                this.type = type
            }
            registry[name] = attribute

            if ((value !is String) || value.isEmpty()) {
                writeToDatabase(PREFIX, name, type)
                return
            }
        }

        if ((value is String) && value.isNotEmpty()) {
            var options = attribute.options
            if (options == null) {
                options = mutableSetOf()
                attribute.options = options
            }

            if (options.add(value)) {
                writeToDatabase(PREFIX, name, type, value)
            }
        }
    }

    private fun writeToDatabase(prefix: String, name: String, type: String, option: String? = null) {
        val collection = prefix + "_" + currentTimeSuffix

        val operation = JsonObject().apply {
            put("type", "UPDATE")
            put("upsert", true)
            put("filter", JsonObject().apply {
                put("name", name)
            })
            put("document", JsonObject().apply {
                put("\$setOnInsert", JsonObject().apply {
                    put("type", type)
                })
                if (option != null) {
                    put("\$addToSet", JsonObject().apply {
                        put("options", option)
                    })
                }
            })
        }

        vertx.eventBus().localSend(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}