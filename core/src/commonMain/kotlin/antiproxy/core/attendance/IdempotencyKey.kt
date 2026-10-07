package antiproxy.core.attendance

import antiproxy.core.crypto.sha256
import antiproxy.core.protocol.SESSION_ID_SIZE
import antiproxy.core.protocol.KEY_ID_SIZE

private val IDEMPOTENCY_PREFIX = "antiproxy-attendance-v1".encodeToByteArray()

/**
 * Compute attendance idempotency key.
 * H = SHA-256("antiproxy-attendance-v1" || authority_device_id || session_id || student_device_key_id)
 * All fields use canonical binary encoding.
 */
fun computeIdempotencyKey(
    authorityDeviceId: ByteArray,
    sessionId: ByteArray,
    studentDeviceKeyId: ByteArray
): ByteArray {
    require(sessionId.size == SESSION_ID_SIZE) { "sessionId must be $SESSION_ID_SIZE bytes" }
    require(studentDeviceKeyId.size == KEY_ID_SIZE) { "studentDeviceKeyId must be $KEY_ID_SIZE bytes" }
    
    val input = ByteArray(IDEMPOTENCY_PREFIX.size + authorityDeviceId.size + sessionId.size + studentDeviceKeyId.size)
    var offset = 0
    IDEMPOTENCY_PREFIX.copyInto(input, offset); offset += IDEMPOTENCY_PREFIX.size
    authorityDeviceId.copyInto(input, offset); offset += authorityDeviceId.size
    sessionId.copyInto(input, offset); offset += sessionId.size
    studentDeviceKeyId.copyInto(input, offset)
    
    return sha256(input)
}
