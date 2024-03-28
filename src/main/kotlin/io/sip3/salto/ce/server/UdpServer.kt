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

package io.sip3.salto.ce.server

import io.sip3.commons.vertx.annotations.ConditionalOnProperty
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.domain.Address
import io.vertx.core.datagram.DatagramSocketOptions
import mu.KotlinLogging

/**
 * Retrieves SIP3 and HEP3 packets
 */
@Instance(singleton = true, worker = true)
@ConditionalOnProperty("/server/udp")
class UdpServer : Server("udp")  {

    private val logger = KotlinLogging.logger {}

    override fun startServer() {
        val options = DatagramSocketOptions().apply {
            isIpV6 = uri.host.matches(Regex("\\[.*]"))
            bufferSize?.let { receiveBufferSize = it }
        }

        vertx.createDatagramSocket(options)
            .handler { packet ->
                val sender = Address().apply {
                    addr = if (options.isIpV6) {
                        packet.sender().host().substringBefore("%")
                    } else {
                        packet.sender().host()
                    }
                    port = packet.sender().port()
                }

                val buffer = packet.data()
                try {
                    onRawPacket(sender, buffer)
                } catch (e: Exception) {
                    logger.error(e) { "UdpServer 'onRawPacket()' failed." }
                }
            }
            .listen(uri.port, uri.host)
            .onFailure { t ->
                logger.error(t) { "UDP connection failed. URI: $uri" }
                throw t
            }
            .onSuccess { logger.info { "Listening on $uri" } }
    }
}