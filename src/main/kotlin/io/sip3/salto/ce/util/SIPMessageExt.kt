package io.sip3.salto.ce.util

import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse

fun SIPMessage.callId(): String? {
    return callId?.callId
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

fun SIPMessage.method(): String? {
    return (this as? SIPRequest)?.requestLine?.method
}

fun SIPMessage.statusCode(): Int? {
    return (this as? SIPResponse)?.statusCode
}