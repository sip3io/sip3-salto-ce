/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import mu.KotlinLogging
import org.apache.commons.net.util.SubnetUtils

object HostRegistry {

    private val logger = KotlinLogging.logger {}

    private var vertx: Vertx? = null
    private lateinit var config: JsonObject
    private var client: io.vertx.ext.mongo.MongoClient? = null

    private var checkPeriod: Long = 30000

    private var hosts = emptyMap<String, String>()
    private var mapping = emptyMap<String, String>()

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
        config.getJsonObject("mongo")?.let {
            client = MongoClient.createShared(vertx!!, it)
        }

        if (client != null) {
            config.getJsonObject("hosts")?.getLong("check-period")?.let {
                checkPeriod = it
            }

            updateHosts()
            if (checkPeriod > 0) {
                vertx!!.setPeriodic(checkPeriod) {
                    updateHosts() }
            }
        }
    }

    fun getHostName(addr: String, port: Int): String? {
        return hosts[addr] ?: hosts["${addr}:${port}"]
    }

    fun getAddrMapping(addr: String): String? {
        return mapping[addr]
    }

    fun save(host: JsonObject) {
        if (client != null) {
            val query = JsonObject().apply {
                put("name", host.getString("name"))
            }
            client!!.replaceDocumentsWithOptions("hosts", query, host, updateOptionsOf(upsert = true)) { asr ->
                if (asr.failed()) {
                    logger.error(asr.cause()) { "MongoClient 'replaceDocuments()' failed." }
                }
            }
        }
    }

    private fun updateHosts() {
        client?.find("hosts", JsonObject()) { asr ->
            if (asr.failed()) {
                logger.error("MongoClient 'find()' failed.", asr.cause())
                return@find
            }
            val tmpHostMap = mutableMapOf<String, String>()
            val tmpMapping = mutableMapOf<String, String>()

            asr.result().forEach { host ->
                try {
                    tmpHostMap.putAll(mapHostToAddr(host))
                    hostMapping(host)?.let { tmpMapping.putAll(it) }
                } catch (e: Exception) {
                    logger.error("Router `mapHostToAddr()` failed. Host: $host")
                }
            }

            hosts = tmpHostMap.toMap()
            mapping = tmpMapping.toMap()
        }
    }

    private fun mapHostToAddr(host: JsonObject): Map<String, String> {
        return mutableMapOf<String, String>().apply {
            val name = host.getString("name")

            host.getJsonArray("addr")?.forEach { addr ->
                addr as String
                put(addr, name)
                if (addr.contains("/")) {
                    SubnetUtils(addr).apply { isInclusiveHostCount = true }.info
                        .allAddresses
                        .forEach { put(it, name) }
                }
            }
        }
    }

    private fun hostMapping(host: JsonObject): Map<String, String>? {
        return host.getJsonArray("mapping")?.associate { addressMapping ->
            addressMapping as JsonObject
            Pair(addressMapping.getString("source"), addressMapping.getString("target"))
        }
    }
}