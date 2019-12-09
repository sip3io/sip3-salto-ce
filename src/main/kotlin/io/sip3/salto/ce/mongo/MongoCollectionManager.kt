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

import io.sip3.commons.util.format
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.mongo.createCollectionAwait
import io.vertx.kotlin.ext.mongo.createIndexAwait
import io.vertx.kotlin.ext.mongo.dropCollectionAwait
import io.vertx.kotlin.ext.mongo.getCollectionsAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Manages MongoDB collections
 */
class MongoCollectionManager : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var client: MongoClient? = null
    private var updatePeriod: Long = 3600000
    private var collections: JsonArray = JsonArray()

    override fun start() {
        config().let { config ->
            config.getString("time-suffix")?.let {
                timeSuffix = DateTimeFormatter.ofPattern(it)
            }

            config.getJsonObject("mongo")?.let { config ->
                client = MongoClient.createShared(vertx, JsonObject().apply {
                    put("connection_string", config.getString("uri") ?: throw IllegalArgumentException("mongo.uri"))
                    put("db_name", config.getString("db") ?: throw IllegalArgumentException("mongo.db"))
                })
                config.getLong("update-period")?.let { updatePeriod = it }
                config.getJsonArray("collections")?.let { collections = it }
            }
        }

        if (client != null) {
            manageCollections()
            vertx.setPeriodic(updatePeriod) { manageCollections() }
        }
    }

    private fun manageCollections() {
        GlobalScope.launch(vertx.dispatcher()) {
            try {
                // Retrieve collections
                var names = client!!.getCollectionsAwait() as MutableList<String>

                // Drop and create collections
                collections.forEach { collection ->
                    // Drop collections
                    dropCollectionsByPrefix(collection as JsonObject, names)

                    // Create collection `${prefix}_${System.currentTimeMillis()}`
                    var name = collection.getString("prefix") + "_${timeSuffix.format(System.currentTimeMillis())}"
                    if (!names.contains(name)) {
                        createCollectionAndIndexes(name, collection.getJsonObject("indexes"))
                        names.add(name)
                    }

                    // Create collection `${prefix}_${System.currentTimeMillis() + updatePeriod}`
                    name = collection.getString("prefix") + "_${timeSuffix.format(System.currentTimeMillis() + updatePeriod)}"
                    if (!names.contains(name)) {
                        createCollectionAndIndexes(name, collection.getJsonObject("indexes"))
                        names.add(name)
                    }
                }
            } catch (e: Exception) {
                logger.error("MongoCollectionManager 'manageCollectionsAwait()' failed.", e)
            }
        }
    }

    private suspend fun dropCollectionsByPrefix(collection: JsonObject, collections: List<String>) {
        collections.asSequence()
                .filter { name -> name.startsWith(collection.getString("prefix")) }
                .sortedDescending()
                .drop(collection.getInteger("max-collections") ?: 1)
                .forEach { name -> client!!.dropCollectionAwait(name) }
    }

    private suspend fun createCollectionAndIndexes(name: String, indexes: JsonObject? = null) {
        // Create collection
        client!!.createCollectionAwait(name)

        // Create ascending indexes if needed
        indexes?.getJsonArray("ascending")?.forEach { index ->
            client!!.createIndexAwait(name, JsonObject().apply {
                put(index as String, 1)
            })
        }

        // Create hashed indexes if needed
        indexes?.getJsonArray("hashed")?.forEach { index ->
            client!!.createIndexAwait(name, JsonObject().apply {
                put(index as String, "hashed")
            })
        }
    }
}