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

package io.sip3.salto.ce.sip

import io.sip3.commons.util.format
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

/**
 * Handles SIP OPTIONS
 */
open class SipOptionsHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val PREFIX = "sip_options"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var recordCallUsersAttributes = false

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("attributes")?.getBoolean("record-call-users")?.let {
            recordCallUsersAttributes = it
        }

        val index = config().getInteger("index")
        vertx.eventBus().localConsumer<SipTransaction>(PREFIX + "_$index") { event ->
            try {
                val transaction = event.body()
                handle(transaction)
            } catch (e: Exception) {
                logger.error("SipOptionsHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(transaction: SipTransaction) {
        writeAttributes(transaction)
        writeToDatabase(PREFIX, transaction)
    }

    open fun writeAttributes(transaction: SipTransaction) {
        val attributes = transaction.attributes
                .toMutableMap()
                .apply {
                    remove(Attributes.src_host)
                    remove(Attributes.dst_host)

                    put(Attributes.method, transaction.cseqMethod)
                    put(Attributes.state, transaction.state)

                    put(Attributes.call_id, "")
                    remove(Attributes.x_call_id)

                    val caller = get(Attributes.caller) ?: transaction.caller
                    put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                    val callee = get(Attributes.callee) ?: transaction.callee
                    put(Attributes.callee, if (recordCallUsersAttributes) callee else "")
                }

        vertx.eventBus().send(RoutesCE.attributes, Pair("sip", attributes), USE_LOCAL_CODEC)
    }

    open fun writeToDatabase(prefix: String, transaction: SipTransaction) {
        val collection = prefix + "_" + timeSuffix.format(transaction.createdAt)

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                put("state", transaction.state)

                put("created_at", transaction.createdAt)
                put("terminated_at", transaction.terminatedAt)

                val src = transaction.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = transaction.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("caller", transaction.caller)
                put("callee", transaction.callee)
                put("call_id", transaction.callId)

                transaction.attributes.forEach { (name, value) -> put(name, value) }
            })
        }

        vertx.eventBus().send(RoutesCE.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}