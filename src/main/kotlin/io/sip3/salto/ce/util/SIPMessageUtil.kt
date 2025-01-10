/*
 * Copyright 2018-2025 SIP3.IO, Corp.
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

package io.sip3.salto.ce.util

import gov.nist.javax.sip.address.SipUri
import gov.nist.javax.sip.message.Content
import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.sip3.salto.ce.sdp.SessionDescriptionParser
import org.restcomm.media.sdp.SessionDescription
import javax.sip.address.TelURL
import javax.sip.address.URI

fun SIPMessage.callId(): String? {
    return callId?.callId
}

fun SIPMessage.branchId(): String? {
    return topmostVia?.branch
}

fun SIPMessage.toUri(): String? {
    return to?.address?.uri?.toString()
}

fun SIPMessage.toUserOrNumber(): String? {
    return to?.address?.uri?.userOrNumber()
}

fun SIPMessage.fromUri(): String? {
    return from?.address?.uri?.toString()
}

fun SIPMessage.fromUserOrNumber(): String? {
    return from?.address?.uri?.userOrNumber()
}

fun SIPMessage.cseqMethod(): String? {
    return cSeq?.method
}

fun SIPMessage.cseqNumber(): Long? {
    return cSeq?.seqNumber
}

fun SIPMessage.method(): String? {
    return (this as? SIPRequest)?.requestLine?.method
}

fun SIPMessage.statusCode(): Int? {
    return (this as? SIPResponse)?.statusCode
}

fun SIPMessage.transactionId(): String {
    return "${callId()}:${branchId()}:${cseqNumber()}"
}

fun SIPMessage.headersMap(): Map<String, String> {
    return mutableMapOf<String, String>().apply {
        (this@headersMap as? SIPRequest)?.let {
            put("request-line", it.requestLine.toString().replace("\r\n", ""))
        }
        (this@headersMap as? SIPResponse)?.let {
            put("status-line", it.statusLine.toString().replace("\r\n", ""))
        }
        headers.forEach { header -> put(header.headerName.lowercase(), header.headerValue) }
    }
}

fun SIPMessage.hasSdp(): Boolean {
    contentTypeHeader?.let { contentType ->
        if (contentType.mediaSubType == "sdp") {
            return true
        } else {
            multipartMimeContent?.contents?.forEach { mimeContent ->
                if (mimeContent.matches("sdp")) {
                    return true
                }
            }
        }
    }

    return false
}

fun SIPMessage.sessionDescription(): SessionDescription? {
    if (this.contentTypeHeader?.mediaSubType == "sdp") {
        return SessionDescriptionParser.parse(this.messageContent)
    } else {
        this.multipartMimeContent?.contents?.forEach { mimeContent ->
            if (mimeContent.matches("sdp")) {
                return SessionDescriptionParser.parse(mimeContent.content.toString())
            }
        }
    }

    return null
}

fun SIPMessage.expires(): Int? {
    return expires?.expires
        ?: contactHeader?.contactParms?.getValue("expires")?.toString()?.toInt()
}

fun URI.userOrNumber() = when (this) {
    is SipUri -> user
    is TelURL -> phoneNumber
    else -> throw IllegalArgumentException("Unsupported URI format: '$this'")
}

fun Content.matches(proto: String): Boolean {
    return contentTypeHeader?.contentSubType?.lowercase()?.contains(proto.lowercase()) ?: false
}