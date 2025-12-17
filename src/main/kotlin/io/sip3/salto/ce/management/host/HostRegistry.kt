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

package io.sip3.salto.ce.management.host

import io.sip3.commons.mongo.MongoClient
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClientUpdateResult
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import mu.KotlinLogging
import org.apache.commons.net.util.SubnetUtils

/**
 * Handles host mappings
 */
object HostRegistry {

    private val logger = KotlinLogging.logger {}

    private const val HOST_COLLECTION = "hosts"
    private var checkPeriod: Long = 30000

    private var vertx: Vertx? = null
    private lateinit var config: JsonObject
    private lateinit var client: io.vertx.ext.mongo.MongoClient
    private var periodicTask: Long? = null

    private var hosts = emptyMap<String, String>()
    private var mappings = emptyMap<String, String>()
    private var features = emptyMap<String, Set<String>>()

    @Synchronized
    fun getInstance(vertx: Vertx, config: JsonObject): HostRegistry {
        if (HostRegistry.vertx == null) {
            HostRegistry.vertx = vertx
            HostRegistry.config = config
            init()
        }

        return this
    }

    @Synchronized
    fun destroy() {
        periodicTask?.let {
            vertx?.cancelTimer(it)
        }

        vertx = null
        periodicTask = null
        client.close()
    }

    private fun init() {
        config.getJsonObject("hosts")?.getLong("check_period")?.let {
            checkPeriod = it
        }
        config.getJsonObject("mongo").let {
            client = MongoClient.createShared(vertx!!, it.getJsonObject("management") ?: it)
        }

        periodicTask = vertx!!.setPeriodic(0L, checkPeriod) {
            updateHosts()
        }
    }

    fun getHostName(addr: String): String? {
        return hosts[addr]
    }

    fun getHostName(addr: String, port: Int): String? {
        return hosts[addr] ?: hosts["${addr}:${port}"]
    }

    fun getAddrMapping(addr: String): String? {
        return mappings[addr]
    }

    fun getFeatures(name: String): Set<String>? {
        return features[name]
    }

    fun save(host: JsonObject): Future<MongoClientUpdateResult> {
        val query = JsonObject().apply {
            put("name", host.getString("name"))
        }

        return client.replaceDocumentsWithOptions(HOST_COLLECTION, query, host, updateOptionsOf(upsert = true))
            .onFailure { t ->
                logger.error(t) { "MongoClient 'removeDocuments()' failed." }
            }
    }

    fun saveAndRemoveDuplicates(host: JsonObject): Future<Long> {
        val promise = Promise.promise<Long>()

        save(host)
            .onFailure { t ->
                logger.error(t) { "HostRegistry 'save()' failed." }
                promise.fail(t)
            }
            .onSuccess { _ ->
                val query = JsonObject().apply {
                    put("\$and", JsonArray().apply {
                        add(JsonObject().apply {
                            put("name", JsonObject().apply {
                                put("\$ne", host.getString("name"))
                            })
                        })
                        add(JsonObject().apply {
                            put("addr", JsonObject().apply {
                                put("\$in", host.getJsonArray("addr"))
                            })
                        })
                    })
                }

                client.removeDocuments(HOST_COLLECTION, query)
                    .onFailure { t ->
                        logger.error(t) { "MongoClient 'removeDocuments()' failed." }
                        promise.fail(t)
                    }
                    .onSuccess { result ->
                        logger.trace { "Duplicates removed: ${result?.removedCount}" }
                        promise.complete(result?.removedCount)
                    }
            }
        return promise.future()
    }

    private fun updateHosts() {
        client.find(HOST_COLLECTION, JsonObject())
            .onFailure { logger.error(it) { "MongoClient 'find()' failed." } }
            .onSuccess { result ->
                val tmpHosts = mutableMapOf<String, String>()
                val tmpMappings = mutableMapOf<String, String>()
                val tmpFeatures = mutableMapOf<String, Set<String>>()

                result.forEach { host ->
                    val name = host.getString("name")

                    try {
                        // Update `hosts`
                        host.getJsonArray("addr")
                            ?.map { it as String }
                            ?.forEach { addr ->
                                tmpHosts[addr] = name
                                if (addr.contains("/")) {
                                    SubnetUtils(addr).apply { isInclusiveHostCount = true }.info
                                        .allAddresses
                                        .forEach { tmpHosts[it] = name }
                                }
                            }

                        // Update `mappings`
                        host.getJsonArray("mapping")
                            ?.map { it as JsonObject }
                            ?.forEach {
                                tmpMappings[it.getString("source")] = it.getString("target")
                            }

                        // Update `features`
                        host.getJsonArray("feature")
                            ?.map { it as String }
                            ?.toSet()
                            ?.let {
                                tmpFeatures[name] = it
                            }
                    } catch (e: Exception) {
                        logger.error(e) { "Router `mapHostToAddr()` failed. Host: $host" }
                    }
                }

                hosts = tmpHosts
                mappings = tmpMappings
                features = tmpFeatures
            }
    }
}