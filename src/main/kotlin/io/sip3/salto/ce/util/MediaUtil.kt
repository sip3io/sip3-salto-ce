package io.sip3.salto.ce.util

object MediaUtil {

    const val R0 = 93.2F
    const val MOS_MIN = 1F
    const val MOS_MAX = 4.5F

    fun computeMos(rFactor: Float): Float {
        return when {
            rFactor < 0 -> MOS_MIN
            rFactor > 100F -> MOS_MAX
            else -> (1 + rFactor * 0.035 + rFactor * (100 - rFactor) * (rFactor - 60) * 0.000007).toFloat()
        }
    }
}