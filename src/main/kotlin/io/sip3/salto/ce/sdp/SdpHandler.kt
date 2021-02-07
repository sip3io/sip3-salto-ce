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

package io.sip3.salto.ce.sdp

import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.util.toIntRange
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localPublish
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.sip.SipTransaction
import io.sip3.salto.ce.util.address
import io.sip3.salto.ce.util.defineRtcpPort
import io.sip3.salto.ce.util.ptime
import io.sip3.salto.ce.util.sessionDescription
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.restcomm.media.sdp.fields.MediaDescriptionField

/**
 * Handles SIP Transactions with SDP
 */
@Instance
class SdpHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var codecs = mapOf<String, Codec>()

    override fun start() {
        readCodecs(config())
        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
            try {
                val config = event.body()
                readCodecs(config)
            } catch (e: Exception) {
                logger.error("SdpHandler `readCodecs()` failed.", e)
            }
        }

        vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.sdp + "_session") { event ->
            try {
                val transaction = event.body()
                handle(transaction)
            } catch (e: Exception) {
                logger.error("SdpHandler 'handle()' failed.", e)
            }
        }
    }

    private fun readCodecs(config: JsonObject) {
        val tmpCodecs = mutableMapOf<String, Codec>()
        config.getJsonArray("codecs")?.map { codecObject ->
            codecObject as JsonObject

            val codec = Codec().apply {
                name = codecObject.getString("name").toUpperCase()
                payloadTypes = codecObject.getJsonArray("payload_types")
                    .flatMap { payloadType ->
                        when (payloadType) {
                            is Int -> setOf(payloadType)
                            is String -> payloadType.toIntRange()
                            else -> throw IllegalArgumentException("Couldn't parse `payload_types`. Unknown type: $payloadType")
                        }
                    }
                    .toSet()
                    .toList()

                clockRate = codecObject.getInteger("clock_rate")

                ie = codecObject.getFloat("ie")
                bpl = codecObject.getFloat("bpl")
            }

            tmpCodecs[codec.name] = codec
        }
        codecs = tmpCodecs
    }

    private fun handle(transaction: SipTransaction) {
        logger.debug { "Execute handle(). TransactionId: ${transaction.id}" }
        val session = SdpSessionDescription().apply {
            callId = transaction.callId
            try {
                request = transaction.request?.sessionDescription()?.getMediaDescription("audio")
            } catch (e: Exception) {
                logger.debug(e) { "Couldn't parse SDP. Message: ${transaction.request}" }
            }

            try {
                response = transaction.response?.sessionDescription()?.getMediaDescription("audio")
            } catch (e: Exception) {
                logger.debug(e) { "Couldn't parse SDP. Message: ${transaction.response}" }
            }
        }

        if (session.request == null || session.response == null) {
            return
        }

        defineCodecs(session)
        send(session)
    }

    private fun defineCodecs(session: SdpSessionDescription) {
        val request = session.request
        val response = session.response

        val payloadTypes = if (request != null && response != null) {
            response.payloadTypes
                .intersect(request.payloadTypes.asIterable())
        } else {
            (request ?: response)?.payloadTypes?.toList()
        }
        requireNotNull(payloadTypes) { "Payload types are undefined. CallID: ${session.callId}" }

        session.codecs = payloadTypes.map { payloadType ->
            var codec: Codec? = null

            // Define Codec by name
            val payload = response?.getFormat(payloadType) ?: request?.getFormat(payloadType)
            if (payload != null) {
                codec = codecs[payload.codec.toUpperCase()]
            }

            // Define Codec by Payload Type
            if (codec == null) {
                codec = codecs.values.firstOrNull { it.payloadTypes.contains(payloadType) }
            }

            // Use default Codec if still Undefined
            return@map codec ?: Codec().apply { this.payloadTypes = listOf(payloadType) }
        }
    }

    private fun send(session: SdpSessionDescription) {
        logger.debug { "Sending SDP. CallID: ${session.callId}, Request media: ${session.requestAddress}, Response media: ${session.responseAddress}" }
        vertx.eventBus().localPublish(RoutesCE.sdp + "_info", session.sdpSessions())
    }

    private class SdpSessionDescription {

        companion object {

            const val DEFAULT_PTIME = 20
        }

        lateinit var callId: String
        lateinit var codecs: List<Codec>

        var request: MediaDescriptionField? = null
        var response: MediaDescriptionField? = null

        val isRtcpMux: Boolean by lazy {
            (response?.isRtcpMux ?: false) && (request?.isRtcpMux ?: false)
        }

        val ptime: Int by lazy {
            return@lazy response?.ptime()
                ?: request?.ptime()
                ?: DEFAULT_PTIME
        }

        val requestAddress: String? by lazy {
            request?.let { "${it.connection.address}:${it.port}" }
        }
        val responseAddress: String? by lazy {
            response?.let { "${it.connection.address}:${it.port}" }
        }

        fun sdpSessions(): Pair<SdpSession, SdpSession> {
            return Pair(sdpSession(request!!), sdpSession(response!!))
        }

        private fun sdpSession(mediaDescription: MediaDescriptionField): SdpSession {
            return SdpSession().apply {
                timestamp = System.currentTimeMillis()

                address = mediaDescription.address()
                rtpPort = mediaDescription.port
                rtcpPort = mediaDescription.defineRtcpPort(isRtcpMux)

                codecs = this@SdpSessionDescription.codecs
                ptime = this@SdpSessionDescription.ptime

                callId = this@SdpSessionDescription.callId
            }
        }
    }
}
