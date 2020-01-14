/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Manages `attributes` collection
 */
open class AttributesHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "attributes"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private lateinit var currentTimeSuffix: String

    private val attributes = mutableMapOf<String, Attribute>()

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
            when (value) {
                is String -> handleStringAttribute(name, value)
                else -> handleAnyAttribute(name, value)
            }
        }
    }

    open fun updateTimeSuffixIfNeeded() {
        val newTimeSuffix = timeSuffix.format(System.currentTimeMillis())
        if (currentTimeSuffix < newTimeSuffix) {
            currentTimeSuffix = newTimeSuffix
            attributes.clear()
        }
    }

    open fun handleStringAttribute(name: String, value: String) {
        var attribute = attributes[name]
        if (attribute == null) {
            attribute = Attribute().apply {
                this.name = name
                this.type = Attribute.TYPE_STRING
            }
            attributes[name] = attribute
        }

        val options = attribute.options
        if (options.add(value)) {
            writeToDatabase(PREFIX, attribute)
        }
    }

    open fun handleAnyAttribute(name: String, value: Any) {
        val type = when (value) {
            is Number -> Attribute.TYPE_NUMBER
            is Boolean -> Attribute.TYPE_BOOLEAN
            else -> {
                logger.warn("Attribute $name will be skipped due to unsupported value type.")
                return
            }
        }

        var attribute = attributes[name]
        if (attribute == null) {
            attribute = Attribute().apply {
                this.name = name
                this.type = type
            }
            attributes[name] = attribute

            writeToDatabase(PREFIX, attribute)
        }
    }

    open fun writeToDatabase(prefix: String, attribute: Attribute) {
        val collection = prefix + "_" + currentTimeSuffix

        val document = JsonObject().apply {
            put("type", "UPDATE")
            put("upsert", true)
            put("filter", JsonObject().apply {
                put("name", attribute.name)
            })
            put("document", JsonObject().apply {
                put("\$setOnInsert", JsonObject().apply {
                    put("type", attribute.type)
                })
                val options = attribute.options
                if (options.isNotEmpty()) {
                    put("\$addToSet", JsonObject().apply {
                        put("options", JsonObject().apply {
                            put("\$each", JsonArray().apply {
                                attribute.options.forEach { add(it) }
                            })
                        })
                    })
                }
            })
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}