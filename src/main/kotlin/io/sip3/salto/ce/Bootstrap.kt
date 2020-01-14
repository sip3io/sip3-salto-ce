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

package io.sip3.salto.ce

import io.sip3.commons.vertx.AbstractBootstrap
import io.sip3.salto.ce.attributes.AttributesHandler
import io.sip3.salto.ce.decoder.Decoder
import io.sip3.salto.ce.decoder.HepDecoder
import io.sip3.salto.ce.mongo.MongoBulkWriter
import io.sip3.salto.ce.mongo.MongoCollectionManager
import io.sip3.salto.ce.router.Router
import io.sip3.salto.ce.server.Server
import io.sip3.salto.ce.sip.SipCallHandler
import io.sip3.salto.ce.sip.SipMessageHandler
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import mu.KotlinLogging
import java.io.File

val USE_LOCAL_CODEC = deliveryOptionsOf(codecName = "local", localOnly = true)

open class Bootstrap : AbstractBootstrap() {

    private val logger = KotlinLogging.logger {}

    override val configLocations = listOf("config.location")

    override fun deployVerticles(config: JsonObject) {
        // Read `vertx.instances`
        val instances = config.getJsonObject("vertx")?.getInteger("instances") ?: 1
        // Deploy verticles
        deployUdfVerticles(config, instances)
        vertx.deployVerticle(MongoCollectionManager::class, config)
        vertx.deployVerticle(MongoBulkWriter::class, config, instances)
        vertx.deployVerticle(AttributesHandler::class, config)
        vertx.deployVerticle(SipCallHandler::class, config, instances)
        vertx.deployVerticle(SipMessageHandler::class, config, instances)
        vertx.deployVerticle(HepDecoder::class, config, instances)
        vertx.deployVerticle(Decoder::class, config, instances)
        vertx.deployVerticle(Router::class, config, instances)
        vertx.deployVerticle(Server::class, config)
    }

    open fun deployUdfVerticles(config: JsonObject, instances: Int = 1) {
        val options = deploymentOptionsOf().apply {
            this.config = config
            this.instances = instances
        }

        val folder = System.getProperty("udf.location")

        File(folder).walkTopDown().filter(File::isFile).forEach { file ->
            logger.info("Deploying UDF from `$file`")
            vertx.deployVerticle(file.absolutePath, options) { asr ->
                if (asr.failed()) {
                    logger.error("Vertx 'deployVerticle()' failed. File: $file", asr.cause())
                }
            }
        }
    }
}