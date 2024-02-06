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

package io.sip3.salto.ce.attributes

import io.sip3.commons.domain.Attribute
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.RoutesCE
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttributesRegistryTest : VertxTest() {

    @Test
    fun `Write STRING attribute`() {
        var attributesRegistry: AttributesRegistry? = null
        runTest(
            deploy = {
                attributesRegistry = AttributesRegistry(vertx, JsonObject())
            },
            execute = {
                val attributes = mapOf<String, Any>("name" to "string")
                attributesRegistry?.handle(Attribute.TYPE_STRING, attributes)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val filter = operation.getJsonObject("filter")
                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("attributes"))
                        assertEquals(Attribute.TYPE_STRING + ".name", filter.getString("name"))
                        assertEquals(Attribute.TYPE_STRING, document.getJsonObject("\$setOnInsert").getString("type"))
                    }
                    if (document.containsKey("\$addToSet")) {
                        context.completeNow()
                    }
                }
            }
        )
    }

    @Test
    fun `Write NUMBER attribute`() {
        var attributesRegistry: AttributesRegistry? = null
        runTest(
            deploy = {
                attributesRegistry = AttributesRegistry(vertx, JsonObject())
            },
            execute = {
                val attributes = mapOf<String, Any>("name" to 42)
                attributesRegistry?.handle(Attribute.TYPE_NUMBER, attributes)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val filter = operation.getJsonObject("filter")
                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("attributes"))
                        assertEquals(Attribute.TYPE_NUMBER + ".name", filter.getString("name"))
                        assertEquals(Attribute.TYPE_NUMBER, document.getJsonObject("\$setOnInsert").getString("type"))
                        assertFalse(document.containsKey("\$addToSet"))
                    }
                    context.completeNow()
                }
            }
        )
    }

    @Test
    fun `Write BOOLEAN attribute`() {
        var attributesRegistry: AttributesRegistry? = null
        runTest(
            deploy = {
                attributesRegistry = AttributesRegistry(vertx, JsonObject())
            },
            execute = {
                val attributes = mapOf<String, Any>("name" to true)
                attributesRegistry?.handle(Attribute.TYPE_BOOLEAN, attributes)
            },
            assert = {
                vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                    val (collection, operation) = event.body()

                    val filter = operation.getJsonObject("filter")
                    val document = operation.getJsonObject("document")

                    context.verify {
                        assertTrue(collection.startsWith("attributes"))
                        assertEquals(Attribute.TYPE_BOOLEAN + ".name", filter.getString("name"))
                        assertEquals(Attribute.TYPE_BOOLEAN, document.getJsonObject("\$setOnInsert").getString("type"))
                        assertFalse(document.containsKey("\$addToSet"))
                    }
                    context.completeNow()
                }
            }
        )
    }
}