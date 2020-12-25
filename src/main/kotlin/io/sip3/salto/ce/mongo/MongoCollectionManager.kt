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

package io.sip3.salto.ce.mongo

import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.setPeriodic
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Manages MongoDB collections
 */
@Instance(singleton = true)
@ConditionalOnProperty("/mongo")
class MongoCollectionManager : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val DEFAULT_MAX_COLLECTIONS = 30
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private lateinit var client: MongoClient
    private var updatePeriod: Long = 3600000
    private var collections: JsonArray = JsonArray()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("mongo").let { config ->
            client = MongoClient.createShared(vertx, JsonObject().apply {
                put("connection_string", config.getString("uri") ?: throw IllegalArgumentException("mongo.uri"))
                put("db_name", config.getString("db") ?: throw IllegalArgumentException("mongo.db"))
            })
            config.getLong("update-period")?.let { updatePeriod = it }
            config.getJsonArray("collections")?.let { collections = it }
        }

        vertx.setPeriodic(0L, updatePeriod) { manageCollections() }
    }

    private fun manageCollections() {
        GlobalScope.launch(vertx.dispatcher()) {
            // Drop and create collections
            collections.forEach { collection ->
                try {
                    // Drop collections
                    dropOldCollectionsByPrefix(collection as JsonObject)

                    // Create collection `${prefix}_${System.currentTimeMillis()}`
                    var name = collection.getString("prefix") + "_${timeSuffix.format(System.currentTimeMillis())}"
                    createCollectionIfNeeded(name, collection.getJsonObject("indexes"))

                    // Create collection `${prefix}_${System.currentTimeMillis() + updatePeriod}`
                    name = collection.getString("prefix") + "_${timeSuffix.format(System.currentTimeMillis() + updatePeriod)}"
                    createCollectionIfNeeded(name, collection.getJsonObject("indexes"))
                } catch (e: Exception) {
                    logger.error(e) { "MongoCollectionManager 'manageCollections()' failed." }
                }
            }
        }
    }

    private suspend fun dropOldCollectionsByPrefix(collection: JsonObject) {
        client.collections.await()
            .filter { name -> name.startsWith(collection.getString("prefix")) }
            .sortedDescending()
            .drop(collection.getInteger("max-collections") ?: DEFAULT_MAX_COLLECTIONS)
            .forEach { name -> client.dropCollection(name).await() }
    }

    private suspend fun createCollectionIfNeeded(name: String, indexes: JsonObject? = null) {
        // Create collection
        if (!client.collections.await().contains(name)) {
            try {
                client.createCollection(name).await()
            } catch (e: Exception) {
                logger.debug(e) { "MongoClient 'createCollection()' failed." }
            }
        }

        // Create collection indexes
        if (indexes != null) {
            val indexCount = client.listIndexes(name).await().count()
            if (indexCount <= 1) {
                createIndexes(name, indexes)
            }
        }
    }

    private suspend fun createIndexes(name: String, indexes: JsonObject) {
        // Create ascending indexes if needed
        indexes.getJsonArray("ascending")?.forEach { index ->
            client.createIndex(name, JsonObject().apply {
                put(index as String, 1)
            }).await()
        }

        // Create hashed indexes if needed
        indexes.getJsonArray("hashed")?.forEach { index ->
            client.createIndex(name, JsonObject().apply {
                put(index as String, "hashed")
            }).await()
        }
    }
}