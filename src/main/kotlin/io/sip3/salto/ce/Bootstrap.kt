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
import io.sip3.salto.ce.decoder.Decoder
import io.sip3.salto.ce.decoder.HepDecoder
import io.sip3.salto.ce.server.Server
import io.sip3.salto.ce.sip.SipMessageHandler
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf

val USE_LOCAL_CODEC = deliveryOptionsOf(codecName = "local", localOnly = true)

open class Bootstrap : AbstractBootstrap() {

    override val configLocations = listOf("config.location")

    override fun deployVerticles(config: JsonObject) {
        // Read `vertx.instances`
        val instances = config.getJsonObject("vertx")?.getInteger("instances") ?: 1
        // Deploy verticles
        vertx.deployVerticle(SipMessageHandler::class, config, instances)
        vertx.deployVerticle(HepDecoder::class, config, instances)
        vertx.deployVerticle(Decoder::class, config, instances)
        vertx.deployVerticle(Server::class, config)
    }
}