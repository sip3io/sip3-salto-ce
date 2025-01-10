/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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

package io.sip3.salto.ce.server

import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import mu.KotlinLogging

/**
 * Abstract server that retrieves SIP3 and HEP3 packets
 */
abstract class AbstractServer : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PROTO_SIP3 = "SIP3"
        const val PROTO_HEP3 = "HEP3"
        val PROTO_HEP2 = byteArrayOf(0x02, 0x10, 0x02)
    }

    private val packetsReceived = Metrics.counter("packets_received")

    override fun start() {
        readConfig()
        startServer()
    }

    abstract fun readConfig()

    abstract fun startServer()

    open fun onRawPacket(sender: Address, buffer: Buffer) {
        packetsReceived.increment()

        if (buffer.length() < 4) return

        // SIP3 and HEP3
        when (buffer.getString(0, 4)) {
            PROTO_SIP3 -> vertx.eventBus().localSend(RoutesCE.sip3, Pair(sender, buffer))
            PROTO_HEP3 -> vertx.eventBus().localSend(RoutesCE.hep3, Pair(sender, buffer))
            else -> {
                // HEP2
                val prefix = buffer.getBytes(0, 3)
                if (prefix.contentEquals(PROTO_HEP2)) {
                    vertx.eventBus().localSend(RoutesCE.hep2, Pair(sender, buffer))
                }
            }
        }
    }
}