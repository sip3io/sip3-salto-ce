/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Manages `attributes` collection
 */
@Instance
open class AttributesHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "attributes"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private lateinit var currentTimeSuffix: String

    private val attributeMap = mutableMapOf<String, Attribute>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        currentTimeSuffix = timeSuffix.format(System.currentTimeMillis())

        vertx.eventBus().localConsumer<Pair<String, Map<String, Any>>>(RoutesCE.attributes) { event ->
            try {
                val (prefix, attributes) = event.body()
                handle(prefix, attributes)
            } catch (e: Exception) {
                logger.error("AttributesHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(prefix: String, attributes: Map<String, Any>) {
        updateTimeSuffixIfNeeded()

        attributes.forEach { (key, value) ->
            val name = "$prefix.$key"
            val type = when (value) {
                is String -> Attribute.TYPE_STRING
                is Number -> Attribute.TYPE_NUMBER
                is Boolean -> Attribute.TYPE_BOOLEAN
                else -> return@forEach
            }

            var attribute = attributeMap[name]
            if (attribute == null) {
                attribute = Attribute().apply {
                    this.name = name
                    this.type = type
                }
                attributeMap[name] = attribute

                writeToDatabase(PREFIX, name, type)
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
    }

    open fun updateTimeSuffixIfNeeded() {
        val newTimeSuffix = timeSuffix.format(System.currentTimeMillis())
        if (currentTimeSuffix < newTimeSuffix) {
            currentTimeSuffix = newTimeSuffix
            attributeMap.clear()
        }
    }

    open fun writeToDatabase(prefix: String, name: String, type: String, option: String? = null) {
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

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}