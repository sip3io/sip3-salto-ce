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

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.sip3.salto.ce.management.component.ComponentRegistry
import io.sip3.salto.ce.management.host.HostRegistry
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class MockKSingletonExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext?) {
        // HostRegistry mock
        mockkObject(HostRegistry)
        every {
            HostRegistry.getInstance(any(), any())
        } returns HostRegistry

        // ComponentRegistry mock
        mockkObject(ComponentRegistry)
        every {
            ComponentRegistry.getInstance(any(), any())
        } returns ComponentRegistry
    }

    override fun afterEach(context: ExtensionContext?) {
        unmockkObject(HostRegistry)
        unmockkObject(ComponentRegistry)
    }
}