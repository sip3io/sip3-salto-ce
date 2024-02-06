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

import io.sip3.commons.util.format
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.MongoExtension
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.coroutines.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.format.DateTimeFormatter

@ExtendWith(MongoExtension::class)
class MongoCollectionManagerTest : VertxTest() {

    companion object {

        val TIME_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    @Test
    fun `Create MongoDB collection`() {
        val collection1 = "test_create_${TIME_SUFFIX.format(System.currentTimeMillis())}"
        val collection2 = "test_create_${TIME_SUFFIX.format(System.currentTimeMillis() + 25 * 3600 * 1000)}"

        runTest(
            deploy = {
                vertx.deployTestVerticle(MongoCollectionManager::class, JsonObject().apply {
                    put("time_suffix", "yyyyMMdd")
                    put("mongo", JsonObject().apply {
                        put("uri", MongoExtension.MONGO_URI)
                        put("db", "sip3-create")
                        put("collections", JsonArray().apply {
                            add(JsonObject().apply {
                                put("prefix", "test_create")
                                put("indexes", JsonObject().apply {
                                    put("ascending", listOf("name"))
                                })
                            })
                        })
                    })
                })
            },
            assert = {
                val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                    put("connection_string", MongoExtension.MONGO_URI)
                    put("db_name", "sip3-create")
                })
                vertx.setPeriodic(500, 100) {
                    mongo.getCollections { asr ->
                        if (asr.succeeded()
                            && asr.result().size == 5
                            && asr.result().contains(collection1)
                            && asr.result().contains(collection2)
                        ) {
                            mongo.listIndexes(collection1) { asr2 ->
                                if (asr2.succeeded()) {
                                    context.verify {
                                        assertTrue(asr2.result().size() > 1)
                                    }
                                }
                            }
                            mongo.listIndexes(collection2) { asr2 ->
                                if (asr2.succeeded()) {
                                    context.verify {
                                        assertTrue(asr2.result().size() > 1)
                                    }
                                }
                            }
                            context.completeNow()
                        }
                    }
                }
            }
        )
    }

    @Test
    fun `Create MongoDB collection indexes`() {
        var mongo: MongoClient
        val collection = "test_indexes_${TIME_SUFFIX.format(System.currentTimeMillis())}"

        runTest(
            deploy = {
                vertx.deployTestVerticle(MongoCollectionManager::class, JsonObject().apply {
                    put("time_suffix", "yyyyMMdd")
                    put("mongo", JsonObject().apply {
                        put("uri", MongoExtension.MONGO_URI)
                        put("db", "sip3-index")
                        put("collections", JsonArray().apply {
                            add(JsonObject().apply {
                                put("prefix", "test_indexes")
                                put("indexes", JsonObject().apply {
                                    put("ascending", listOf("name"))
                                })
                            })
                        })
                    })
                })
            },
            assert = {
                mongo = MongoClient.createShared(vertx, JsonObject().apply {
                    put("connection_string", MongoExtension.MONGO_URI)
                    put("db_name", "sip3-index")
                })

                mongo.createCollection(collection).await()

                vertx.setPeriodic(500, 100) {
                    mongo.listIndexes(collection) { asr ->
                        if (asr.succeeded() && asr.result().size() > 1) {
                            context.completeNow()
                        }
                    }
                }
            }
        )
    }

    @Test
    fun `Read MongoDB collection hint`() {
        runTest(
            deploy = {
                vertx.deployTestVerticle(MongoCollectionManager::class, JsonObject().apply {
                    put("time_suffix", "yyyyMMdd")
                    put("mongo", JsonObject().apply {
                        put("uri", MongoExtension.MONGO_URI)
                        put("db", "sip3-hint")
                        put("collections", JsonArray().apply {
                            add(JsonObject().apply {
                                put("prefix", "test_hint")
                                put("indexes", JsonObject().apply {
                                    put("ascending", listOf("name"))
                                })
                                put("hint", JsonObject().apply {
                                    put("call_id", "hashed")
                                })
                            })
                        })
                    })
                })
            },
            assert = {
                vertx.setPeriodic(500, 100) {
                    vertx.eventBus().localRequest<JsonObject?>(RoutesCE.mongo_collection_hint, "test_hint") { asr ->
                        if (asr.succeeded() && asr.result().body() != null) {
                            context.verify {
                                assertEquals("hashed", asr.result().body()?.getString("call_id"))
                            }

                            vertx.eventBus().localRequest<JsonObject?>(RoutesCE.mongo_collection_hint, "test_hint_not_set") { asr2 ->
                                if (asr2.succeeded() && asr2.result().body() == null) {
                                    context.completeNow()
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}