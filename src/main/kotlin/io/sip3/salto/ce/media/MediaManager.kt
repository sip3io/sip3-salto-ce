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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.media.MediaControl
import io.sip3.commons.domain.media.Recording
import io.sip3.commons.domain.media.SdpSession
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localPublish
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.sip.SipTransaction
import io.vertx.core.AbstractVerticle
import mu.KotlinLogging

/**
 * Manages media feature
 */
@Instance
open class MediaManager : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var recordingEnabled: Boolean = false

    private var instances: Int = 1

    override fun start() {
        config().getJsonObject("recording")?.let { config ->
            config.getBoolean("enabled")?.let {
                recordingEnabled = it
            }
        }

        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        vertx.eventBus().localConsumer<SipTransaction>(RoutesCE.media + "_sdp") { event ->
            try {
                val transaction = event.body()
                handleSipTransaction(transaction)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleSipTransaction()' failed." }
            }
        }
    }

    open fun handleSipTransaction(transaction: SipTransaction) {
        vertx.eventBus().localRequest<SdpSession>(RoutesCE.sdp + "_session", transaction) { asr ->
            if (asr.failed()) {
                logger.debug(asr.cause()) { "MediaManager 'handleSipTransaction()' failed. " }
                return@localRequest
            }

            try {
                val sdpSession = asr.result().body()
                handleSdpSession(transaction, sdpSession)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleSdpSession()' failed." }
            }
        }
    }

    open fun handleSdpSession(transaction: SipTransaction, sdpSession: SdpSession) {
        createMediaControl(transaction, sdpSession) { mediaControl ->
            vertx.eventBus().localPublish(RoutesCE.media + "_control", mediaControl)
        }
    }

    open fun createMediaControl(
        transaction: SipTransaction,
        sdpSession: SdpSession,
        onComplete: (MediaControl) -> Unit,
    ) {
        val mediaControl = MediaControl().apply {
            timestamp = System.currentTimeMillis()
            callId = transaction.callId
            this.sdpSession = sdpSession

            if (recordingEnabled) {
                recording = Recording()
            }
        }

        onComplete.invoke(mediaControl)
    }
}
