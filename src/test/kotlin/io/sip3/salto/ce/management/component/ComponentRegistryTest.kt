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

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.util.*

@ExtendWith(MongoExtension::class)
class ComponentRegistryTest : VertxTest() {

    companion object {

        const val MONGO_DB = "sip3-test-components"

        val COMPONENT_1 = JsonObject().apply {
            put("name", "sip3-captain")
            put("deployment_id", UUID.randomUUID().toString())
            put("type", "captain")
            put("uri", URI("udp://127.0.0.1:15090").toString())
            put("connected_to", "sip3-salto")

            put("registered_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
            put("remote_updated_at", System.currentTimeMillis() - 100L)

            put("config", JsonObject().apply {
                put("key", "value")
            })
        }
    }

    @Test
    fun `Validate ComponentRegistry 'save()' method `() {
        lateinit var componentRegistry: ComponentRegistry
        lateinit var mongo: MongoClient
        runTest(
            deploy = {
                mongo = MongoClient.createShared(vertx, JsonObject().apply {
                    put("connection_string", MongoExtension.MONGO_URI)
                    put("db_name", MONGO_DB)
                })

                componentRegistry = ComponentRegistry.getInstance(vertx, JsonObject().apply {
                    put("mongo", JsonObject().apply {
                        put("management", JsonObject().apply {
                            put("uri", MongoExtension.MONGO_URI)
                            put("db", MONGO_DB)
                        })
                    })
                    put("management", JsonObject().apply {
                        put("expiration_delay", 100L)
                        put("cleanup_timeout", 500L)
                    })
                })
            },
            execute = {
                componentRegistry.save(COMPONENT_1)
            },
            assert = {
                vertx.setPeriodic(300L, 100L) {
                    mongo.findOne("components", JsonObject(), JsonObject())
                        .onSuccess { saved ->
                            if (saved == null) return@onSuccess

                            context.verify {
                                COMPONENT_1.fieldNames().forEach { key ->
                                    assertEquals(COMPONENT_1.getValue(key), saved.getValue(key))
                                }
                            }
                        }
                }

                vertx.setTimer(700L) {
                    mongo.count("components", JsonObject())
                        .onSuccess { count ->
                            if (count < 1) {
                                context.completeNow()
                            }
                        }
                }
            }
        )
    }
}