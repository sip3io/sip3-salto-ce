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

package io.sip3.salto.ce.sdp

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.util.IpUtil
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.util.callId
import io.sip3.salto.ce.util.matches
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import mu.KotlinLogging
import org.restcomm.media.sdp.SessionDescription
import org.restcomm.media.sdp.SessionDescriptionParser
import org.restcomm.media.sdp.attributes.RtpMapAttribute
import org.restcomm.media.sdp.fields.MediaDescriptionField

class SdpHandler : CoroutineVerticle() {

    private val logger = KotlinLogging.logger {}

    private var codecs = mapOf<String, Codec>()
    private val sessions = mutableMapOf<String, SdpSessionDescription>()

    override suspend fun start() {
        readCodecs(config)
        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
            try {
                val config = event.body()
                readCodecs(config)
            } catch (e: Exception) {
                logger.error("SdpHandler `readCodecs()` failed.", e)
            }
        }

        vertx.eventBus().localConsumer<SIPMessage>(RoutesCE.sdp_session_update) { event ->
            try {
                val message = event.body()
                handleSdp(message)
            } catch (e: Exception) {
                logger.error("SdpHandler 'handleSdp()' failed.", e)
            }
        }

        vertx.eventBus().localConsumer<String>(RoutesCE.sdp_session_terminate) { event ->
            try {
                val sessionKey = event.body()
                sessions.remove(sessionKey)
            } catch (e: Exception) {
                logger.error("SdpHandler sdpSession remove failed.", e)
            }
        }
    }

    private fun readCodecs(config: JsonObject) {
        val tmpCodecs = mutableMapOf<String, Codec>()
        config.getJsonArray("codecs")?.forEach { codecObject ->
            val codec = (codecObject as JsonObject).mapTo(Codec::class.java)
            tmpCodecs[codec.name!!] = codec
        }
        codecs = tmpCodecs
    }

    private fun handleSdp(message: SIPMessage) {
        val sessionDescription: SessionDescription = getSessionDescription(message) ?: return

        val callId = message.callId()!!
        sessions.getOrPut(callId) { SdpSessionDescription(callId) }.apply {
            when (message) {
                is SIPRequest -> {
                    request = sessionDescription
                }
                is SIPResponse -> {
                    response = sessionDescription
                }
            }

            if (isFilled()) {
                sendSdpSession(this)
            }
        }
    }

    private fun sendSdpSession(sdpSessionDescription: SdpSessionDescription) {
        val requestMediaDescription = sdpSessionDescription.request.getMediaDescription("audio")
        val responseMediaDescription = sdpSessionDescription.response.getMediaDescription("audio")

        val callId = sdpSessionDescription.callId
        val codec = getCodec(requestMediaDescription, responseMediaDescription)
        val requestSdpSession = createSdpSession(callId, requestMediaDescription, codec)
        val responseSdpSession = createSdpSession(callId, responseMediaDescription, codec)

        // Send to Management Socket
        vertx.eventBus().send(RoutesCE.sdp_session, listOf(requestSdpSession, responseSdpSession), USE_LOCAL_CODEC)
    }

    private fun getCodec(request: MediaDescriptionField, response: MediaDescriptionField): Codec {
        val payloadTypes = request.payloadTypes.intersect(response.payloadTypes.asIterable())
        val payloadType = payloadTypes.first()
        val payload: RtpMapAttribute? = response.getFormat(payloadType)
                ?: request.getFormat(payloadType)

        val codec = codecs[payload?.codec]
                ?: codecs.values.firstOrNull { it.payloadType.toInt() == payloadType }
                ?: Codec()

        // Replace with values from SDP
        codec.payloadType = payloadType.toByte()
        payload?.let { codec.clockRate = it.clockRate }
        return codec
    }

    private fun createSdpSession(callId: String, mediaDescription: MediaDescriptionField, codec: Codec): SdpSession {
        return SdpSession().apply {
            id = getSdpSessionId(mediaDescription)
            timestamp = System.currentTimeMillis()

            payloadType = codec.payloadType
            codecName = codec.name
            clockRate = codec.clockRate
            codecIe = codec.ie
            codecBpl = codec.bpl

            this.callId = callId
        }
    }

    private fun getSdpSessionId(mediaDescription: MediaDescriptionField): Long {
        val addr = IpUtil.convertToInt(mediaDescription.connection.address).toLong()
        val port = mediaDescription.port.toLong()
        return (addr shl 32) or port
    }

    private fun getSessionDescription(message: SIPMessage): SessionDescription? {
        if (message.contentTypeHeader?.mediaSubType == "sdp") {
            return SessionDescriptionParser.parse(message.messageContent)
        } else {
            message.multipartMimeContent?.contents?.forEach { mimeContent ->
                if (mimeContent.matches("sdp")) {
                    return SessionDescriptionParser.parse(mimeContent.content.toString())
                }
            }
        }

        return null
    }

    private class SdpSessionDescription(val callId: String) {

        lateinit var request: SessionDescription
        lateinit var response: SessionDescription

        fun isFilled(): Boolean {
            return ::request.isInitialized && ::response.isInitialized
        }
    }
}