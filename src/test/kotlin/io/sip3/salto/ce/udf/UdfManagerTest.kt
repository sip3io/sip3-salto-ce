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

package io.sip3.salto.ce.udf

import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.localSend
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.copyTo

class UdfManagerTest : VertxTest() {

    companion object {

        const val UDF_LOCATION = "src/test/resources/udf/UdfManagerTest"
    }


    private lateinit var tmpDir: Path

    @BeforeEach
    fun createTemporaryDirectory() {
        tmpDir = Files.createTempDirectory("sip3-udf-manager")
        System.setProperty("udf.location", tmpDir.toAbsolutePath().toString())
    }

    @Test
    fun `Deploy and re-deploy Groovy UDF`() {
        val message = "Groovy is awesome"
        val counter = AtomicInteger()

        runTest(
            deploy = {
                vertx.orCreateContext.config().put("udf", JsonObject().apply {
                    put("check_period", 1000)
                })
                UdfManager(vertx).start(tmpDir.toAbsolutePath().toString())
            },
            execute = {
                var script = Paths.get(UDF_LOCATION, "UdfManagerTestV1.groovy")
                script.copyTo(tmpDir.resolve("UdfManagerTest.groovy"), overwrite = true)
                vertx.setPeriodic(200) {
                    if (counter.get() == 1 && script == Paths.get(UDF_LOCATION, "UdfManagerTestV1.groovy")) {
                        script = Paths.get(UDF_LOCATION, "UdfManagerTestV2.groovy")
                        script.copyTo(tmpDir.resolve("UdfManagerTest.groovy"), overwrite = true)
                    }
                }
                vertx.setPeriodic(500) {
                    vertx.eventBus().localSend("groovy", message)
                }
            },
            assert = {
                vertx.eventBus().localConsumer<String>("groovy1") { counter.compareAndSet(0, 1) }
                vertx.eventBus().localConsumer<String>("groovy2") { counter.compareAndSet(1, 2) }
                vertx.setPeriodic(200) {
                    if (counter.get() == 2) {
                        context.completeNow()
                    }
                }
            }
        )
    }

    @AfterEach
    fun deleteTemporaryDirectory() {
        Files.deleteIfExists(tmpDir.resolve("UdfManagerTest.groovy"))
        Files.deleteIfExists(tmpDir)
    }
}