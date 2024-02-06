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

package io.sip3.salto.ce

import io.sip3.commons.vertx.AbstractBootstrap
import io.sip3.salto.ce.udf.UdfManager
import io.vertx.core.json.JsonObject

open class Bootstrap : AbstractBootstrap() {

    override val configLocations = listOf("config.location", "codecs.location")

    override suspend fun deployVerticles(config: JsonObject) {
        super.deployVerticles(config)

        System.getProperty("udf.location")?.let { path ->
            UdfManager(vertx).start(path)
        }
    }
}