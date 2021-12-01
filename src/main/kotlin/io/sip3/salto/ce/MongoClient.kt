/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

package io.sip3.salto.ce

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient

object MongoClient {

    fun createShared(vertx: Vertx, config: JsonObject): MongoClient {
        // Let's do properties remapping because Vert.x MongoDB client options are such a disaster in terms of grouping and naming conventions.
        val uri = config.getString("uri") ?: throw IllegalArgumentException("uri")
        val db = config.getString("db") ?: throw IllegalArgumentException("db")

        return MongoClient.createShared(vertx, JsonObject().apply {
            put("connection_string", uri)
            put("db_name", db)

            config.getJsonObject("auth")?.let { auth ->
                auth.getString("user")?.let {
                    put("username", it)
                }
                auth.getString("password")?.let {
                    put("password", it)
                }
            }

            config.getJsonObject("ssl")?.let { ssl ->
                ssl.getBoolean("enabled")?.let {
                    put("ssl", it)
                }
                ssl.getString("ca-path")?.let {
                    put("caPath", it)
                }
                ssl.getString("cert-path")?.let {
                    put("certPath", it)
                }
                ssl.getString("key-path")?.let {
                    put("keyPath", it)
                }
                ssl.getBoolean("trust-all")?.let {
                    put("trustAll", it)
                }
            }
        }, "$uri:$db")
    }
}