package antiproxy.core.attendance

/**
 * Detects obvious wall-clock rollback by comparing current time against
 * the last recorded time. Persists last-known time through the provided storage callback.
 *
 * This is defense-in-depth. It cannot detect all clock manipulation.
 */
class ClockGuard(
    private val loadLastTime: () -> Long?,      // load persisted last-known time
    private val saveLastTime: (Long) -> Unit     // persist new last-known time
) {
    /**
     * Check if current time appears valid (not rolled back).
     * Updates persisted time if valid.
     * Returns false if obvious rollback detected.
     *
     * @param currentTimeSeconds current UTC Unix seconds
     * @param maxAcceptableRollbackSeconds small tolerance for minor drift (default 60)
     */
    fun checkAndUpdate(currentTimeSeconds: Long, maxAcceptableRollbackSeconds: Long = 60): Boolean {
        val lastTime = loadLastTime()
        if (lastTime != null && currentTimeSeconds < lastTime - maxAcceptableRollbackSeconds) {
            return false  // Obvious rollback detected
        }
        // Update if time has advanced (or first check)
        if (lastTime == null || currentTimeSeconds > lastTime) {
            saveLastTime(currentTimeSeconds)
        }
        return true
    }
}
