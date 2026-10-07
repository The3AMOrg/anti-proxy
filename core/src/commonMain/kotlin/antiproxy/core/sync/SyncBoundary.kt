package antiproxy.core.sync

import antiproxy.core.storage.SyncEvent
import antiproxy.core.storage.SyncState

/**
 * Synchronization boundary interface for the backend team.
 * The backend team implements the HTTP transport; this subsystem provides
 * the event shape and idempotency guarantees.
 *
 * Each SyncEvent contains:
 * - eventId: unique event identifier (UUID)
 * - payload: serialized attendance event (opaque bytes for transport)
 * - idempotencyKey: SHA-256 hash for server-side deduplication
 * - createdAt: event creation timestamp
 * - syncState: current sync lifecycle state
 * - retryCount: number of sync attempts
 * - nextAttemptAt: scheduled next retry (null if not scheduled)
 * - lastError: last sync error message (null on success)
 *
 * Idempotency contract:
 * The idempotency key is computed as:
 *   SHA-256("antiproxy-attendance-v1" || authority_device_id || session_id || student_device_key_id)
 *
 * The backend MUST use this key to deduplicate events.
 * The local outbox is crash-safe: committed events survive process death.
 * Attendance verification MUST NOT depend on sync success.
 */
interface SyncTransport {
    /**
     * Attempt to send a sync event to the backend.
     * Returns true if the backend acknowledged receipt.
     * Implementation is expected to use HTTPS with TLS.
     */
    suspend fun send(event: SyncEvent): Boolean
}

/**
 * Manages the sync outbox lifecycle.
 * UI/backend teams consume this to drive synchronization.
 */
interface SyncManager {
    /** Get all events pending sync. */
    fun pendingEvents(limit: Int = 100): List<SyncEvent>

    /** Mark event as successfully synced. */
    fun markSynced(eventId: String)

    /** Mark event as permanently failed (will not retry). */
    fun markFailed(eventId: String, error: String)

    /** Mark event as sending (in-flight). */
    fun markSending(eventId: String)

    /** Reset sending events back to pending (e.g., after crash recovery). */
    fun resetStaleInFlight(olderThanSeconds: Long)
}
