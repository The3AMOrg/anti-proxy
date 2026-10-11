package antiproxy.core.attendance

import kotlin.math.roundToInt

fun rssiMean(samples: List<Int>): Double {
    require(samples.isNotEmpty()) { "Samples list cannot be empty" }
    return samples.average()
}

fun rssiVariance(samples: List<Int>): Double {
    require(samples.isNotEmpty()) { "Samples list cannot be empty" }
    val mean = rssiMean(samples)
    return samples.sumOf { (it - mean) * (it - mean) } / samples.size
}

/**
 * Mean RSSI in one unsigned byte, as half-dB steps below 0 dBm: 0 to -127.5 dBm, which covers every
 * BLE reading. (Protocol v1 used a signed byte that stopped at -64 dBm, about a metre away.)
 * Out-of-range values clamp, so the policy, not the encoder, decides what is too far.
 */
fun toQ1(meanDbm: Double): Byte = (-meanDbm * 2).roundToInt().coerceIn(0, 255).toByte()

fun fromQ1(q1: Byte): Double = -(q1.toInt() and 0xFF) / 2.0

/** Variance clamps at the top of the range; anything that large is rejected by the policy anyway. */
fun toQ8_8(varianceDbm2: Double): UShort {
    require(varianceDbm2 >= 0.0) { "Variance can't be negative" }
    return (varianceDbm2 * 256).roundToInt().coerceAtMost(0xFFFF).toUShort()
}

fun fromQ8_8(q8_8: UShort): Double {
    return q8_8.toInt() / 256.0
}
