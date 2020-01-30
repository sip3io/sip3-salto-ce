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

package io.sip3.salto.ce.sdp

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.util.callId
import io.sip3.salto.ce.util.sdpSessionId
import io.sip3.salto.ce.util.sessionDescription
import io.sip3.salto.ce.util.transactionId
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.restcomm.media.sdp.SessionDescription
import org.restcomm.media.sdp.attributes.RtpMapAttribute
import org.restcomm.media.sdp.fields.MediaDescriptionField

class SdpHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val CMD_HANDLE = "handle"
        const val CMD_TERMINATE = "terminate"
    }

    private var expirationDelay: Long = 1000
    private var durationTimeout: Long = 3600000

    private var codecs = mapOf<String, Codec>()
    private val sessions = mutableMapOf<String, SdpSessionDescription>()

    override fun start() {
        config().getJsonObject("sip")?.getJsonObject("call")?.let { config ->
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("duration-timeout")?.let {
                durationTimeout = it
            }
        }

        readCodecs(config())
        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
            try {
                val config = event.body()
                readCodecs(config)
            } catch (e: Exception) {
                logger.error("SdpHandler `readCodecs()` failed.", e)
            }
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredSessions()
        }

        vertx.eventBus().localConsumer<Pair<String, Any>>(RoutesCE.sdp_session) { event ->
            try {
                val (cmd, obj) = event.body()
                when (cmd) {
                    CMD_HANDLE -> handle(obj as SIPMessage)
                    CMD_TERMINATE -> terminate(obj as String)
                    else -> throw IllegalArgumentException("Unsupported SdpHandler command: '$cmd'")
                }
            } catch (e: Exception) {
                logger.error("SdpHandler 'handle()' or 'terminate()' failed.", e)
            }
        }
    }

    private fun readCodecs(config: JsonObject) {
        val tmpCodecs = mutableMapOf<String, Codec>()
        config.getJsonArray("codecs")?.forEach { codecObject ->
            val codec = (codecObject as JsonObject).mapTo(Codec::class.java)
            tmpCodecs[codec.name] = codec
        }
        codecs = tmpCodecs
    }

    private fun terminateExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.filterValues { it.createdAt + durationTimeout < now }
                .forEach { (transactionId, _) -> terminate(transactionId) }
    }

    private fun terminate(transactionId: String) {
        sessions.remove(transactionId)
    }

    private fun handle(message: SIPMessage) {
        val sessionDescription: SessionDescription = message.sessionDescription() ?: return

        val transactionId = message.transactionId()
        val session = sessions.getOrPut(transactionId) {
            SdpSessionDescription().apply { this.callId = message.callId()!! }
        }

        when (message) {
            is SIPRequest -> session.request = sessionDescription.getMediaDescription("audio")
            is SIPResponse -> session.response = sessionDescription.getMediaDescription("audio")
        }

        if (session.request != null && session.response != null) {
            session.codec = defineCodec(session.request!!, session.response!!)
            send(session)
        }
    }

    private fun send(session: SdpSessionDescription) {
        val now = System.currentTimeMillis()

        val sdpSessions = listOf(session.request!!.sdpSessionId(), session.response!!.sdpSessionId())
                .map { id ->
                    SdpSession().apply {
                        this.id = id
                        timestamp = now
                        this.callId = session.callId
                        this.codec = session.codec
                    }
                }

        vertx.eventBus().send(RoutesCE.sdp_info, sdpSessions, USE_LOCAL_CODEC)
    }

    private fun defineCodec(request: MediaDescriptionField, response: MediaDescriptionField): Codec {
        val payloadType = request.payloadTypes
                .intersect(response.payloadTypes.asIterable())
                .first()

        val payload: RtpMapAttribute? = response.getFormat(payloadType) ?: request.getFormat(payloadType)

        val codec = codecs[payload?.codec]
                ?: codecs.values.firstOrNull { it.payloadType.toInt() == payloadType }
                ?: Codec()

        // Replace with values from SDP
        codec.payloadType = payloadType.toByte()
        payload?.let { codec.clockRate = it.clockRate }
        return codec
    }

    private class SdpSessionDescription {

        val createdAt = System.currentTimeMillis()

        lateinit var callId: String
        lateinit var codec: Codec

        var request: MediaDescriptionField? = null
        var response: MediaDescriptionField? = null
    }
}