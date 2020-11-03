package io.sip3.salto.ce.util

import io.sip3.salto.ce.domain.Address

object MediaUtil {

    const val R0 = 93.2F
    const val MOS_MIN = 1F
    const val MOS_MAX = 4.5F

    fun rtpSessionId(srcAddr: Address, dstAddr: Address, ssrc: Long): Long {
        val srcPort = srcAddr.port.toLong()
        val dstPort = dstAddr.port.toLong()

        return (srcPort shl 48) or (dstPort shl 32) or ssrc
    }

    fun computeMos(rFactor: Float): Float {
        return when {
            rFactor < 0 -> MOS_MIN
            rFactor > 100F -> MOS_MAX
            else -> (1 + rFactor * 0.035 + rFactor * (100 - rFactor) * (rFactor - 60) * 0.000007).toFloat()
        }
    }
}