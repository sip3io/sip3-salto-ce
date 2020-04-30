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

package io.sip3.salto.ce

import io.sip3.commons.vertx.AbstractBootstrap
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.deploymentOptionsOf
import mu.KotlinLogging
import java.io.File

open class Bootstrap : AbstractBootstrap() {

    private val logger = KotlinLogging.logger {}

    override val configLocations = listOf("config.location", "codecs.location")

    override fun deployVerticles(config: JsonObject) {
        super.deployVerticles(config)
        deployUdfVerticles(config, config.getJsonObject("vertx")?.getInteger("instances") ?: 1)
    }

    open fun deployUdfVerticles(config: JsonObject, instances: Int = 1) {
        val options = deploymentOptionsOf().apply {
            this.config = config
            this.instances = instances
        }

        System.getProperty("udf.location")?.let { folder ->
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
}