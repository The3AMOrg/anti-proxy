package antiproxy.core.attendance

/**
 * Replay protection interface. Implemented by platform storage.
 * Checks whether an attendance proof has already been successfully processed
 * for a given session + device combination.
 */
interface ReplayGuard {
    /**
     * Check if this session+device combination has already been recorded.
     * Returns true if this is a replay (already seen).
     */
    fun isReplay(sessionId: ByteArray, deviceKeyId: ByteArray): Boolean

    /**
     * Record that this session+device combination has been processed.
     * Called within the attendance transaction, before ACK.
     */
    fun recordAttendance(sessionId: ByteArray, deviceKeyId: ByteArray)
}
