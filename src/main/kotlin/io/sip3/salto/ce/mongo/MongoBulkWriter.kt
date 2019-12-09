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

package io.sip3.salto.ce.mongo

import io.sip3.salto.ce.RoutesCE
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.BulkOperation
import io.vertx.ext.mongo.BulkWriteOptions
import io.vertx.ext.mongo.MongoClient
import mu.KotlinLogging

/**
 * Writes bulks of documents to MongoDB
 */
class MongoBulkWriter : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var client: MongoClient? = null
    private var bulkSize = 0
    private val bulkWriteOptions = BulkWriteOptions(false)

    private val documents = mutableMapOf<String, MutableList<BulkOperation>>()
    private var size = 0

    override fun start() {
        config().getJsonObject("mongo")?.let { config ->
            client = MongoClient.createShared(vertx, JsonObject().apply {
                put("connection_string", config.getString("uri") ?: throw IllegalArgumentException("mongo.uri"))
                put("db_name", config.getString("db") ?: throw IllegalArgumentException("mongo.db"))
            })
            bulkSize = config.getInteger("bulk-size")
        }

        if (client != null) {
            vertx.eventBus().localConsumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { bulkOperation ->
                try {
                    val (collection, document) = bulkOperation.body()
                    handle(collection, document)
                } catch (e: Exception) {
                    logger.error("MongoBulkWriter 'handle()' failed.", e)
                }
            }
        }
    }

    override fun stop() {
        flushToDatabase()
    }

    private fun handle(collection: String, document: JsonObject) {
        val bulkOperations = documents.computeIfAbsent(collection) { mutableListOf() }
        document.apply {
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
        bulkOperations.add(BulkOperation(document))
        size++
        if (size >= bulkSize) {
            flushToDatabase()
        }
    }

    private fun flushToDatabase() {
        documents.forEach { (collection, bulkOperations) ->
            client!!.bulkWriteWithOptions(collection, bulkOperations, bulkWriteOptions) { asr ->
                if (asr.failed()) {
                    logger.error("MongoClient 'bulkWriteWithOptions()' failed.", asr.cause())
                }
            }
        }
        documents.clear()
        size = 0
    }
}