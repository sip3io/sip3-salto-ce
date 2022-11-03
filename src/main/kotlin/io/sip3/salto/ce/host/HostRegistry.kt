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

package io.sip3.salto.ce.host

import io.sip3.salto.ce.MongoClient
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import mu.KotlinLogging
import org.apache.commons.net.util.SubnetUtils

/**
 * Handles host mappings
 */
object HostRegistry {

    private val logger = KotlinLogging.logger {}

    private var checkPeriod: Long = 30000

    private var vertx: Vertx? = null
    private lateinit var config: JsonObject
    private var client: io.vertx.ext.mongo.MongoClient? = null

    private var hosts = emptyMap<String, String>()
    private var mappings = emptyMap<String, String>()
    private var features = emptyMap<String, Set<String>>()

    @Synchronized
    fun getInstance(vertx: Vertx, config: JsonObject): HostRegistry {
        if (this.vertx == null) {
            this.vertx = vertx
            this.config = config
            init()
        }

        return this
    }

    private fun init() {
        config.getJsonObject("hosts")?.getLong("check-period")?.let {
            checkPeriod = it
        }
        config.getJsonObject("mongo")?.let {
            client = MongoClient.createShared(vertx!!, it.getJsonObject("management") ?: it)
        }

        if (client != null) {
            updateHosts()
            vertx!!.setPeriodic(checkPeriod) {
                updateHosts()
            }
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

    fun save(host: JsonObject) {
        if (client != null) {
            val query = JsonObject().apply {
                put("name", host.getString("name"))
            }
            client!!.replaceDocumentsWithOptions("hosts", query, host, updateOptionsOf(upsert = true)) { asr ->
                if (asr.failed()) {
                    logger.error(asr.cause()) { "MongoClient 'replaceDocumentsWithOptions()' failed." }
                }
            }
        }
    }

    fun getConfig(name: String): Future<JsonObject> {
        val promise = Promise.promise<JsonObject>()
        val query = JsonObject().apply {
            put("name", name)
        }
        client!!.findOne("configuration", query, JsonObject())
            .onFailure { promise.fail(it) }
            .onSuccess { promise.complete(it ?: JsonObject()) }

        return promise.future()
    }

    private fun updateHosts() {
        client?.find("hosts", JsonObject()) { asr ->
            if (asr.failed()) {
                logger.error(asr.cause()) { "MongoClient 'find()' failed." }
                return@find
            }

            val tmpHosts = mutableMapOf<String, String>()
            val tmpMappings = mutableMapOf<String, String>()
            val tmpFeatures = mutableMapOf<String, Set<String>>()

            asr.result().forEach { host ->
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