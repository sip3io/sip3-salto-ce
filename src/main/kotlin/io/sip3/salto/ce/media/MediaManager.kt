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

import io.sip3.commons.domain.SdpSession
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
@Instance(singleton = true)
class MediaManager : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    private var recordAll: Boolean = false

    private var instances: Int = 1

    override fun start() {
        config().getJsonObject("record")?.let { config ->
            config.getBoolean("record-all")?.let {
                recordAll = it
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
        vertx.eventBus().localRequest<Pair<SdpSession, SdpSession>>(RoutesCE.sdp + "_session", transaction) { asr ->
            if (asr.failed()) {
                logger.error(asr.cause()) { "MediaManager 'handleSipTransaction()' failed. " }
                return@localRequest
            }

            try {
                val sdpSessions = asr.result().body()
                handleSdpSessions(transaction, sdpSessions)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleSdpSessions()' failed." }
            }
        }
    }

    open fun handleSdpSessions(transaction: SipTransaction, sdpSessions: Pair<SdpSession, SdpSession>) {
        // TODO: change to `localSend` if possible
        // Publish SDP Session for consumers
        vertx.eventBus().localPublish(RoutesCE.sdp + "_info", sdpSessions)

        createMediaControl(transaction, sdpSessions) { mediaControl ->
            vertx.eventBus().localPublish(RoutesCE.media + "_control", mediaControl)
        }

    }

    open fun createMediaControl(
        transaction: SipTransaction,
        sdpSessions: Pair<SdpSession, SdpSession>,
        onComplete: (MediaControl) -> Unit,
    ) {
        //TODO: do some staff to create media control session
        val mediaControl = MediaControl()
        onComplete.invoke(mediaControl)
    }

}

typealias MediaControl = Any