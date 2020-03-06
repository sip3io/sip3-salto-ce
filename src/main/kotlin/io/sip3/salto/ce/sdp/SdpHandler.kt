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

import io.sip3.commons.domain.Codec
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.sip.SipTransaction
import io.sip3.salto.ce.util.sdpSessionId
import io.sip3.salto.ce.util.sessionDescription
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.restcomm.media.sdp.attributes.RtpMapAttribute
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

        vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.sdp_session) { event ->
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
        config.getJsonArray("codecs")?.forEach { codecObject ->
            val codec = (codecObject as JsonObject).mapTo(Codec::class.java)
            tmpCodecs[codec.name] = codec
        }
        codecs = tmpCodecs
    }

    private fun handle(transaction: SipTransaction) {
        val session = SdpSessionDescription().apply {
            callId = transaction.callId
            request = transaction.request!!.sessionDescription()?.getMediaDescription("audio") ?: return
            response = transaction.response!!.sessionDescription()?.getMediaDescription("audio") ?: return
        }

        defineCodec(session)
        send(session)
    }

    private fun send(session: SdpSessionDescription) {
        val now = System.currentTimeMillis()

        val sdpSessions = session.sdpSessionIds
                .map { sdpSessionId ->
                    SdpSession().apply {
                        id = sdpSessionId
                        timestamp = now
                        callId = session.callId
                        codec = session.codec
                        ptime = session.ptime
                    }
                }

        vertx.eventBus().send(RoutesCE.sdp_info, sdpSessions, USE_LOCAL_CODEC)
    }

    private fun defineCodec(session: SdpSessionDescription) {
        val request = session.request
        val response = session.response

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

        session.codec = codec
    }

    private class SdpSessionDescription {

        companion object {

            const val DEFAULT_PTIME = 20
        }

        lateinit var callId: String
        lateinit var codec: Codec

        lateinit var request: MediaDescriptionField
        lateinit var response: MediaDescriptionField

        val ptime: Int by lazy {
            return@lazy response.ptime?.time
                    ?: request.ptime?.time
                    ?: DEFAULT_PTIME
        }

        val sdpSessionIds: List<Long> by lazy {
            listOf(request.sdpSessionId(), response.sdpSessionId())
        }
    }
}