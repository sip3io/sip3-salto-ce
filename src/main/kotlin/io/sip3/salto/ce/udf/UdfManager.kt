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

package io.sip3.salto.ce.udf

import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Manages User-Defined Functions Deployment
 */
class UdfManager(val vertx: Vertx) {

    private val logger = KotlinLogging.logger {}

    companion object {

        val DEPLOYMENT_OPTIONS = DeploymentOptions()
    }

    private var checkPeriod: Long = 10000

    private val deployments = mutableMapOf<String, Deployment>()
    private var lastChecked: Long = 0

    fun start(path: String) {
        vertx.orCreateContext.config().let { config ->
            config.getJsonObject("udf")?.getLong("check_period")?.let {
                checkPeriod = it
            }

            DEPLOYMENT_OPTIONS.apply {
                this.config = config
                this.instances = config.getJsonObject("vertx")?.getInteger("instances") ?: 1
            }
        }

        vertx.setPeriodic(0, checkPeriod) {
            GlobalScope.launch(vertx.dispatcher() as CoroutineContext) {
                manage(path)
            }
        }
    }

    private suspend fun manage(path: String) {
        val now = System.currentTimeMillis()

        // 1. Walk through the UDF directory and update `deployments`
        File(path).walkTopDown().filter(File::isFile).forEach { file ->
            var deploymentId: String? = null

            if (file.lastModified() >= lastChecked) {
                try {
                    logger.info { "Deploying new UDF. File: `$file`" }
                    deploymentId = vertx.deployVerticle(file.absolutePath, DEPLOYMENT_OPTIONS).await()
                } catch (e: Exception) {
                    logger.error("Vertx 'deployVerticle()' failed. File: $file", e)
                }
            }

            when {
                deploymentId != null -> {
                    deployments.put(file.absolutePath, Deployment(deploymentId))?.let { deployment ->
                        try {
                            logger.info { "Removing the old UDF. File: `$file`, " }
                            vertx.undeploy(deployment.id).await()
                        } catch (e: Exception) {
                            logger.error("Vertx 'undeploy()' failed. File: $file", e)
                        }
                    }
                }
                else -> {
                    deployments[file.absolutePath]?.let { deployment -> deployment.lastUpdated = now }
                }
            }
        }

        // 2. Walk through `deployments` and remove ones that expired
        deployments.filterValues { deployment -> deployment.lastUpdated < now }.forEach { (file, deployment) ->
            try {
                logger.info { "Removing an expired UDF. File: `$file`, " }
                vertx.undeploy(deployment.id).await()
            } catch (e: Exception) {
                logger.error("Vertx 'undeploy()' failed. File: $file", e)
            }
            deployments.remove(file)
        }

        lastChecked = now
    }

    inner class Deployment(var id: String) {

        var lastUpdated = System.currentTimeMillis()
    }
}