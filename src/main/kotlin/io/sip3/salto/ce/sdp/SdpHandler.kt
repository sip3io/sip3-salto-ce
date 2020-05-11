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
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
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

        if (session.request == null && session.response == null) {
            return
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

        logger.debug { "Sending SDP. CallID: ${session.callId}, Request media: ${session.requestAddress}, Response media: ${session.responseAddress}" }
        vertx.eventBus().localRequest<Any>(RoutesCE.sdp_info, sdpSessions)
    }

    private fun defineCodec(session: SdpSessionDescription) {
        val request = session.request
        val response = session.response

        val payloadType = if (request != null && response != null) {
            request.payloadTypes
                    .intersect(response.payloadTypes.asIterable())
                    .first()
        } else {
            request?.payloadTypes?.firstOrNull() ?: response?.payloadTypes?.first()!!
        }

        val payload: RtpMapAttribute? = response?.getFormat(payloadType) ?: request?.getFormat(payloadType)

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

        var request: MediaDescriptionField? = null
        var response: MediaDescriptionField? = null

        val ptime: Int by lazy {
            return@lazy response?.ptime?.time
                    ?: request?.ptime?.time
                    ?: DEFAULT_PTIME
        }

        val requestAddress:String? by lazy {
            request?.let { "${it.connection.address}:${it.port}" }
        }
        val responseAddress:String? by lazy {
            response?.let { "${it.connection.address}:${it.port}" }
        }

        val sdpSessionIds: List<Long> by lazy {
            listOfNotNull(request?.sdpSessionId(), response?.sdpSessionId())
        }
    }
}