package antiproxy.core.scheduling

import antiproxy.core.protocol.RETRY_MAX_SLOTS
import antiproxy.core.protocol.RETRY_JITTER_BOUND
import antiproxy.core.protocol.MAX_RETRY_COUNT
import antiproxy.core.protocol.SLOT_DURATION_MS
import java.security.SecureRandom
import kotlin.math.min

/**
 * Exponential backoff retry scheduler with bounded jitter.
 * delay_slots = min(2^r, RETRY_MAX_SLOTS) + J
 * where r = retry number, J = Uniform(0, RETRY_JITTER_BOUND)
 */
object RetryScheduler {
    private val secureRandom = SecureRandom()

    /**
     * Compute retry delay in milliseconds.
     * @param retryNumber 0-based retry count
     * @return delay in milliseconds, or null if max retries exceeded
     */
    fun computeDelayMs(retryNumber: Int): Long? {
        if (retryNumber >= MAX_RETRY_COUNT) return null
        require(retryNumber >= 0) { "retryNumber must be non-negative" }

        val baseSlots = min(1 shl retryNumber, RETRY_MAX_SLOTS)
        val jitter = secureRandom.nextInt(RETRY_JITTER_BOUND + 1)
        return (baseSlots + jitter).toLong() * SLOT_DURATION_MS
    }

    /** Check if more retries are available. */
    fun canRetry(retryNumber: Int): Boolean = retryNumber < MAX_RETRY_COUNT
}
