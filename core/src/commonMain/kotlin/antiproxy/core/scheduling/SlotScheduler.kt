package antiproxy.core.scheduling

import antiproxy.core.protocol.NUM_SLOTS
import antiproxy.core.protocol.SLOT_DURATION_MS
import java.security.SecureRandom

/**
 * Randomized connection slot scheduler.
 * Each student chooses k <- Uniform({0,...,199}) and attempts connection at:
 *   session_discovery_time + k * 100ms
 *
 * This is an application-level scheduler, NOT Slotted ALOHA at the BLE physical layer.
 */
object SlotScheduler {
    private val secureRandom = SecureRandom()

    /** Pick a random slot in [0, NUM_SLOTS). */
    fun pickSlot(): Int = secureRandom.nextInt(NUM_SLOTS)

    /** Compute delay in milliseconds from discovery time to connection attempt. */
    fun slotDelayMs(slot: Int): Long {
        require(slot in 0 until NUM_SLOTS) { "slot must be in [0, $NUM_SLOTS)" }
        return slot.toLong() * SLOT_DURATION_MS
    }
}
