package io.sip3.salto.ce.mongo

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.salto.ce.MongoExtension
import io.sip3.salto.ce.Routes
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
                    vertx.eventBus().send(Routes.mongo_bulk_writer, Pair("test", JsonObject().apply {
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