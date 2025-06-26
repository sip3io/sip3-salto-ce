/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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

package io.sip3.salto.ce.management.component

import io.sip3.commons.mongo.MongoClient
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import mu.KotlinLogging

/**
 * Handles SIP3 components
 */
object ComponentRegistry {

    private val logger = KotlinLogging.logger {}

    private const val COLLECTION = "components"

    private var expirationDelay = 30000L
    private var cleanupTimeout = 300000L

    private var vertx: Vertx? = null
    private lateinit var config: JsonObject
    private lateinit var client: io.vertx.ext.mongo.MongoClient

    @Synchronized
    fun getInstance(vertx: Vertx, config: JsonObject): ComponentRegistry {
        if (ComponentRegistry.vertx == null) {
            ComponentRegistry.vertx = vertx
            ComponentRegistry.config = config
            init()
        }

        return this
    }

    private fun init() {
        config.getJsonObject("management")?.let { components ->
            components.getLong("expiration_delay")?.let {
                expirationDelay = it
            }

            components.getLong("cleanup_timeout")?.let {
                cleanupTimeout = it
            }
        }

        config.getJsonObject("mongo").let {
            client = MongoClient.createShared(vertx!!, it.getJsonObject("management") ?: it)
        }

        vertx!!.setPeriodic(expirationDelay) {
           removeExpired()
        }
    }

    private fun removeExpired() {
        val query = JsonObject().apply {
            put("updated_at", JsonObject().apply {
                put("\$lt", System.currentTimeMillis() - cleanupTimeout)
            })
        }

        client.removeDocuments(COLLECTION, query)
            .onFailure { logger.error(it) { "MongoClient 'removeDocuments()' failed." } }
            .onSuccess { result ->
                result?.removedCount?.takeIf { it > 0L }?.let { removedCount ->
                    logger.info { "Removed expired components: $removedCount" }
                }}
    }

    fun list(): Future<List<JsonObject>> {
        return client.find(COLLECTION, JsonObject())
    }

    fun save(component: JsonObject) {
        val query = JsonObject().apply {
            put("deployment_id", component.getString("deployment_id"))
        }

        client.replaceDocumentsWithOptions(COLLECTION, query, component, updateOptionsOf(upsert = true))
            .onFailure { logger.error(it) { "MongoClient 'replaceDocumentsWithOptions()' failed." } }
    }

    fun remove(deploymentId: String) {
        val query = JsonObject().apply {
            put("deployment_id", deploymentId)
        }
        client.removeDocument(COLLECTION, query)
            .onFailure { logger.error(it) { "MongoClient 'removeDocument()' failed." } }
    }
}