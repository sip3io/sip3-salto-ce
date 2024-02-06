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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.Recording
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localRequest
import io.sip3.commons.vertx.util.localSend
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.sip.SipTransaction
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging

/**
 * Manages media feature
 */
@Instance(singleton = true)
open class MediaManager : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    protected var recordingEnabled: Boolean = false

    override fun start() {
        config().getJsonObject("recording")?.let { config ->
            config.getBoolean("enabled")?.let {
                recordingEnabled = it
            }
        }

        vertx.eventBus().localConsumer<JsonObject>(RoutesCE.config_change) { event ->
            try {
                val config = event.body()
                onConfigChange(config)
            } catch (e: Exception) {
                logger.error(e) { "MediaManager 'onConfigChange()' failed." }
            }
        }

        vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.media + "_sdp") { event ->
            try {
                val transaction = event.body()
                handleSipTransaction(transaction)
            } catch (e: Exception) {
                logger.error(e) { "MediaManager 'handleSipTransaction()' failed." }
            }
        }
    }

    open fun onConfigChange(config: JsonObject) {
        config.getJsonObject("recording")?.getBoolean("enabled")?.let {
            recordingEnabled = it
        }

        if (!recordingEnabled) {
            vertx.eventBus().localSend(RoutesCE.media + "_recording_reset", JsonObject())
        }
    }

    open fun handleSipTransaction(transaction: SipTransaction) {
        vertx.eventBus().localRequest<SdpSession?>(RoutesCE.sdp + "_session", transaction) { asr ->
            if (asr.succeeded()) {
                try {
                    val sdpSession = asr.result().body()
                    if (sdpSession != null) {
                        handleSdpSession(transaction, sdpSession)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "MediaManager 'handleSdpSession()' failed." }
                }
            }
        }
    }

    open fun handleSdpSession(transaction: SipTransaction, sdpSession: SdpSession) {
        val mediaControl = createMediaControl(transaction, sdpSession)
        vertx.eventBus().localPublish(RoutesCE.media + "_control", mediaControl)
    }

    open fun createMediaControl(transaction: SipTransaction, sdpSession: SdpSession): MediaControl {
        return MediaControl().apply {
            timestamp = transaction.createdAt

            callId = transaction.callId
            caller = (transaction.attributes["caller"] as? String) ?: transaction.caller
            callee = (transaction.attributes["callee"] as? String) ?: transaction.callee

            this.sdpSession = sdpSession

            if (recordingEnabled) {
                recording = Recording()
            }
        }
    }
}
