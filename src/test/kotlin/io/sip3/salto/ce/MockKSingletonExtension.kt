/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.sip3.salto.ce.hosts.HostRegistry
import org.junit.jupiter.api.extension.*

class MockKSingletonExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext?) {
        mockkObject(HostRegistry)
        every {
            HostRegistry.getInstance(any(), any())
        } returns HostRegistry
    }

    override fun afterEach(context: ExtensionContext?) {
        unmockkObject(HostRegistry)
    }
}