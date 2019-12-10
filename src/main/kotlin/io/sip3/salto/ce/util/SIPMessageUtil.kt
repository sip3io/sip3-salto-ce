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

package io.sip3.salto.ce.util

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse

fun SIPMessage.callId(): String? {
    return callId?.callId
}

fun SIPMessage.branchId(): String? {
    return topmostVia?.branch
}

fun SIPMessage.toUri(): String? {
    return to?.address?.uri?.toString()
}

fun SIPMessage.fromUri(): String? {
    return from?.address?.uri?.toString()
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

fun SIPMessage.transactionId() : String {
    return "${callId()}:${branchId()}:${cseqNumber()}"
}

fun SIPMessage.headersMap() : Map<String, String> {
    return mutableMapOf<String, String>().apply {
        headers.forEach { header -> put(header.headerName.toLowerCase(), header.headerValue) }
    }
}