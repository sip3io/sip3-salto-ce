/*
 * Copyright 2018-2023 SIP3.IO, Corp.
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

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.coroutines.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MongoExtension::class)
class HostRegistryTest : VertxTest() {

    companion object {

        val HOST_1 = JsonObject().apply {
            put("name", "host_1")
            put("addr", JsonArray().apply {
                add("1.1.1.1/32")
                add("2.2.2.0/30")
            })
        }

        val HOST_2 = JsonObject().apply {
            put("name", "host_2")
            put("addr", JsonArray().apply {
                add("4.4.4.4")
                add("5.5.5.5")
            })
            put("mapping", JsonArray().apply {
                add(JsonObject().apply {
                    put("source", "3.3.3.1")
                    put("target", "4.4.4.4")
                })
                add(JsonObject().apply {
                    put("source", "2.2.2.2")
                    put("target", "5.5.5.5")
                })
                put("feature", JsonArray().apply {
                    add("feature1")
                    add("feature2")
                })
            })
        }

        val HOST_3 = JsonObject().apply {
            put("name", "host_3")
            put("addr", JsonArray().apply {
                add("6.6.6.6")
                add("7.7.7.7")
            })
            put("mapping", JsonArray().apply {
                add(JsonObject().apply {
                    put("source", "7.7.7.7")
                    put("target", "6.6.6.6")
                })
            })
            put("feature", JsonArray().apply {
                add("feature1")
                add("feature2")
            })
        }

        val MONGO_DB = "sip3"
    }

    @Test
    fun `Validate HostRegistry methods`() {
        var hostRegistry: HostRegistry? = null

        runTest(
            deploy = {
                val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                    put("connection_string", MongoExtension.MONGO_URI)
                    put("db_name", MONGO_DB)
                })

                mongo.save("hosts", HOST_1).await()
                mongo.save("hosts", HOST_2).await()
                mongo.close()

                hostRegistry = HostRegistry.getInstance(vertx, JsonObject().apply {
                    put("mongo", JsonObject().apply {
                        put("management", JsonObject().apply {
                            put("uri", MongoExtension.MONGO_URI)
                            put("db", "sip3")
                        })
                    })
                    put("hosts", JsonObject().apply {
                        put("check_period", 500L)
                    })
                })
                hostRegistry!!.save(HOST_3)
            },
            assert = {
                vertx.setPeriodic(100L) {
                    if (hostRegistry?.getHostName("1.1.1.1", 5060) != null
                        && hostRegistry?.getHostName("6.6.6.6") != null) {
                        context.verify {
                            assertEquals(HOST_1.getString("name"), hostRegistry?.getHostName("1.1.1.1", 5060), "1")
                            assertEquals(HOST_1.getString("name"), hostRegistry?.getHostName("2.2.2.0/30", 5060), "2")
                            assertEquals(HOST_1.getString("name"), hostRegistry?.getHostName("2.2.2.1", 5060), "3")

                            assertEquals(HOST_2.getString("name"), hostRegistry?.getHostName("4.4.4.4", 15053), "4")
                            assertEquals("5.5.5.5", hostRegistry?.getAddrMapping("2.2.2.2"), "5")
                            assertEquals("feature1", hostRegistry?.getFeatures(HOST_2.getString("name"))?.first())
                            assertEquals("feature2", hostRegistry?.getFeatures(HOST_2.getString("name"))?.last())

                            assertEquals(HOST_3.getString("name"), hostRegistry?.getHostName("6.6.6.6"))
                        }
                        context.completeNow()
                    }
                }
            }
        )
    }
}