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

package io.sip3.salto.ce.mongo

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MongoExtension::class)
class MongoBulkWriterTest : VertxTest() {

    @Test
    fun `Write document to MongoDB`() {
        val document = JsonObject().apply {
            put("name", "test")
        }
        runTest(
                deploy = {
                    vertx.deployTestVerticle(MongoBulkWriter::class, JsonObject().apply {
                        put("mongo", JsonObject().apply {
                            put("uri", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                            put("db", "sip3")
                            put("bulk-size", 1)
                        })
                    })
                },
                execute = {
                    vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair("test", JsonObject().apply {
                        put("document", document)
                    }), USE_LOCAL_CODEC)
                },
                assert = {
                    val mongo = MongoClient.createShared(vertx, JsonObject().apply {
                        put("connection_string", "mongodb://${MongoExtension.HOST}:${MongoExtension.PORT}")
                        put("db_name", "sip3")
                    })
                    vertx.setPeriodic(100) {
                        mongo.find("test", JsonObject()) { asr ->
                            if (asr.succeeded()) {
                                val documents = asr.result()
                                if (documents.isNotEmpty()) {
                                    context.verify {
                                        assertEquals(document, documents[0])
                                    }
                                    context.completeNow()
                                }
                            }
                        }
                    }
                }
        )
    }
}