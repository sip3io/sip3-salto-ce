/*
 * Copyright 2018-2026 SIP3.IO, Corp.
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

package io.sip3.salto.ce.domain

import io.vertx.core.json.JsonObject
import java.net.URI

open class Component(val deploymentId: String, val name: String, val type: String) {

        companion object {

                const val TYPE_CAPTAIN = "captain"
                const val TYPE_SALTO = "salto"

                const val EVENT_ADDED = "component_added"
                const val EVENT_EXPIRED = "component_expired"
        }
        var registeredAt = System.currentTimeMillis()
        var updatedAt: Long = System.currentTimeMillis()
        var remoteUpdatedAt: Long = 0L

        lateinit var uri: URI
        var version: String? = null
        var config = JsonObject()

        var mediaEnabled = false
        var host: String? = null

        val isNew: Boolean
                get() = registeredAt == updatedAt

        override fun toString(): String {
            return "Component(deploymentId=$deploymentId, name=$name, uri=$uri)"
        }
    }
