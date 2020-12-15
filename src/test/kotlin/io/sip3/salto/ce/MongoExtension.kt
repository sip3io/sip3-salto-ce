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

import de.flapdoodle.embed.mongo.MongodExecutable
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.MongodConfig
import de.flapdoodle.embed.mongo.config.Net
import de.flapdoodle.embed.mongo.distribution.Version
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.ServerSocket

class MongoExtension : BeforeAllCallback, AfterAllCallback {

    companion object {

        const val HOST = "127.0.0.1"
        val PORT: Int = ServerSocket(0).use { it.localPort }
    }

    private lateinit var mongo: MongodExecutable

    override fun beforeAll(context: ExtensionContext?) {
        val config = MongodConfig.builder().version(Version.V4_0_12)
            .net(Net(HOST, PORT, false))
            .build()

        mongo = MongodStarter.getDefaultInstance().prepare(config)
        mongo.start()
    }

    override fun afterAll(context: ExtensionContext?) {
        mongo.stop()
    }
}