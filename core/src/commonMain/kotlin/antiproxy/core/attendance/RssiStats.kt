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

fun toQ1(meanDbm: Double): Byte {
    require(meanDbm in -64.0..63.5) { "Mean out of representable Q1 range" }
    return (meanDbm * 2).roundToInt().toByte()
}

fun fromQ1(q1: Byte): Double {
    return q1.toInt() / 2.0
}

fun toQ8_8(varianceDbm2: Double): UShort {
    require(varianceDbm2 in 0.0..<256.0) { "Variance out of representable Q8.8 range" }
    return (varianceDbm2 * 256).roundToInt().toUShort()
}

fun fromQ8_8(q8_8: UShort): Double {
    return q8_8.toInt() / 256.0
}
