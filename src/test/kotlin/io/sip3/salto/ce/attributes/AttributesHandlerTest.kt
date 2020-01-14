/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttributesHandlerTest : VertxTest() {

    @Test
    fun `Write STRING attribute`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(AttributesHandler::class)
                },
                execute = {
                    val attributes = mapOf<String, Any>("name" to "string")
                    vertx.eventBus().send(RoutesCE.attributes, Pair(Attribute.TYPE_STRING, attributes), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()
                        val filter = document.getJsonObject("filter")
                        document = document.getJsonObject("document")
                        context.verify {
                            assertTrue(collection.startsWith("attributes"))
                            assertEquals(Attribute.TYPE_STRING + ".name", filter.getString("name"))
                            assertEquals(Attribute.TYPE_STRING, document.getJsonObject("\$setOnInsert").getString("type"))
                            assertTrue(document.containsKey("\$addToSet"))
                        }
                        context.completeNow()
                    }
                }
        )
    }

    @Test
    fun `Write NUMBER attribute`() {
        runTest(
                deploy = {
                    vertx.deployTestVerticle(AttributesHandler::class)
                },
                execute = {
                    val attributes = mapOf<String, Any>("name" to 42)
                    vertx.eventBus().send(RoutesCE.attributes, Pair(Attribute.TYPE_NUMBER, attributes), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()
                        val filter = document.getJsonObject("filter")
                        document = document.getJsonObject("document")
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
        runTest(
                deploy = {
                    vertx.deployTestVerticle(AttributesHandler::class)
                },
                execute = {
                    val attributes = mapOf<String, Any>("name" to true)
                    vertx.eventBus().send(RoutesCE.attributes, Pair(Attribute.TYPE_BOOLEAN, attributes), USE_LOCAL_CODEC)
                },
                assert = {
                    vertx.eventBus().consumer<Pair<String, JsonObject>>(RoutesCE.mongo_bulk_writer) { event ->
                        var (collection, document) = event.body()
                        val filter = document.getJsonObject("filter")
                        document = document.getJsonObject("document")
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