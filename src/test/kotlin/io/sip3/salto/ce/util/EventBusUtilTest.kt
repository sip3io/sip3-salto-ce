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

package io.sip3.salto.ce.util

import io.sip3.commons.vertx.test.VertxTest
import org.junit.jupiter.api.Test

class EventBusUtilTest : VertxTest() {

    @Test
    fun `Define and test event bus consumer`() {
        runTest(
                deploy = {
                    vertx.eventBus().localConsumer<String>("test") {}
                },
                assert = {
                    vertx.setPeriodic(100) {
                        if (vertx.eventBus().consumes("test")) {
                            context.completeNow()
                        }
                    }
                }
        )
    }
}