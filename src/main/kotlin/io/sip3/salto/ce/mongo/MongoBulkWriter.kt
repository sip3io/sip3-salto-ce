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

package io.sip3.salto.ce.mongo

import io.sip3.commons.mongo.MongoClient
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.BulkOperation
import io.vertx.ext.mongo.BulkWriteOptions
import io.vertx.ext.mongo.WriteOption
import mu.KotlinLogging

/**
 * Sends bulks of operations to MongoDB
 */
@Instance
open class MongoBulkWriter : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private lateinit var client: io.vertx.ext.mongo.MongoClient
    private var bulkSize = 0
    private val bulkWriteOptions = BulkWriteOptions(false)

    private val operations = mutableMapOf<String, MutableList<BulkOperation>>()
    private var size = 0

    override fun start() {
        config().getJsonObject("mongo").let { config ->
            client = MongoClient.createShared(vertx, config)
            bulkSize = config.getInteger("bulk_size")
            config.getInteger("write_option")?.let { writeOption ->
                bulkWriteOptions.writeOption = WriteOption.values()[writeOption]
            }
        }

        vertx.eventBus().localConsumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { bulkOperation ->
            try {
                val (collection, operation) = bulkOperation.body()
                handle(collection, operation)
            } catch (e: Exception) {
                logger.error(e) { "MongoBulkWriter 'handle()' failed." }
            }
        }
    }

    override fun stop() {
        flushToDatabase()
    }

    open fun handle(collection: String, operation: JsonObject) {
        operation.apply {
            if (!containsKey("type")) {
                put("type", "INSERT")
            }
            if (!containsKey("multi")) {
                put("multi", false)
            }
            if (!containsKey("upsert")) {
                put("upsert", false)
            }
        }
        val bulkOperations = operations.getOrPut(collection) { mutableListOf() }
        bulkOperations.add(BulkOperation(operation))
        size++
        if (size >= bulkSize) {
            flushToDatabase()
        }
    }

    private fun flushToDatabase() {
        operations.forEach { (collection, bulkOperations) ->
            client.bulkWriteWithOptions(collection, bulkOperations, bulkWriteOptions) { asr ->
                if (asr.failed()) {
                    logger.error(asr.cause()) { "MongoClient 'bulkWriteWithOptions()' failed." }
                }
            }
        }
        operations.clear()
        size = 0
    }
}