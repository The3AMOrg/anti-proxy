package antiproxy.core.integration

import antiproxy.core.protocol.Session

/** Authority session lifecycle state. */
enum class SessionState {
    IDLE,
    CREATING,
    ACTIVE,
    STOPPING,
    ERROR
}

/**
 * Authority-side API for UI team integration.
 * The UI team calls these methods; the BLE/crypto subsystem implements them.
 */
interface AuthorityApi {
    /** Get current session state. */
    fun getSessionState(): SessionState

    /** Get active session info, or null if no active session. */
    fun getActiveSession(): Session?

    /** Get attendance count for the current session. */
    fun getAttendanceCount(): Int

    /**
     * Start a new attendance session.
     * Creates session, begins BLE advertising.
     * Returns the created session on success.
     */
    suspend fun startSession(): Session

    /** Stop the current session and BLE advertising. */
    suspend fun stopSession()
}
