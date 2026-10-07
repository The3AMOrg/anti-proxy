package antiproxy.core.attendance

/**
 * RSSI acceptance policy applied by Authority.
 * Policy parameters are versioned and configurable.
 */
data class RssiPolicy(
    val policyVersion: Int,
    val minMeanRssi: Double,        // minimum mean RSSI in dBm (e.g., -80.0)
    val maxVariance: Double,         // maximum variance in dBm² (e.g., 100.0)
    val minSampleCount: Int,         // minimum number of RSSI samples
    val anomalyThreshold: Double? = null  // optional anomaly detection threshold
) {
    init {
        require(minSampleCount >= 1) { "minSampleCount must be >= 1" }
    }

    /**
     * Check if RSSI evidence meets policy requirements.
     * Returns true if evidence is acceptable.
     */
    fun evaluate(meanDbm: Double, varianceDbm2: Double, sampleCount: Int): Boolean {
        if (sampleCount < minSampleCount) return false
        if (meanDbm < minMeanRssi) return false
        if (varianceDbm2 > maxVariance) return false
        if (anomalyThreshold != null && varianceDbm2 > anomalyThreshold) return false
        return true
    }

    companion object {
        /** Default policy for M1. */
        val DEFAULT = RssiPolicy(
            policyVersion = 1,
            minMeanRssi = -85.0,
            maxVariance = 200.0,
            minSampleCount = 10
        )
    }
}
