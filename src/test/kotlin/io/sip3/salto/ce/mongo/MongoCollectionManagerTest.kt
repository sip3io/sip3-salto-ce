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

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.setPeriodic
import io.sip3.salto.ce.MongoExtension
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MongoExtension::class)
class MongoCollectionManagerTest : VertxTest() {

    @Test
    fun `Create MongoDB collection`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(MongoCollectionManager::class, JsonObject().apply {
                        put("mongo", JsonObject().apply {
                            put("uri", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                            put("db", "sip3")
                            put("collections", JsonArray().apply {
                                add(JsonObject().apply {
                                    put("prefix", "test")
                                })
                            })
                        })
                    })
                },
                assert = {
                    val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                        put("connection_string", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                        put("db_name", "sip3")
                    })
                    vertx.setPeriodic(500, 100) {
                        mongo.getCollections { asr ->
                            if (asr.succeeded()) {
                                val collection = asr.result().firstOrNull { name -> name.startsWith("test") }
                                if (collection != null) {
                                    context.completeNow()
                                }
                            }
                        }
                    }
                }
        )
    }
}