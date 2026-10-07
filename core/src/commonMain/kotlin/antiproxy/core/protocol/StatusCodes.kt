package antiproxy.core.protocol

/**
 * Protocol status codes returned by Authority to Student via Status characteristic.
 * Values are normative wire format bytes.
 */
enum class StatusCode(val code: Byte) {
    SUCCESS(0x00),
    MALFORMED_FRAME(0x01),
    UNSUPPORTED_VERSION(0x02),
    UNKNOWN_SESSION(0x03),
    SESSION_EXPIRED(0x04),
    NONCE_MISMATCH(0x05),
    UNKNOWN_DEVICE(0x06),
    DEVICE_REVOKED(0x07),
    INVALID_SIGNATURE(0x08),
    REPLAY(0x09),
    INVALID_RSSI_EVIDENCE(0x0A),
    POLICY_REJECTED(0x0B),
    DUPLICATE_ATTENDANCE(0x0C),
    TRUST_LEASE_EXPIRED(0x0D),
    MTU_OR_FRAGMENT_ERROR(0x0E),
    INTERNAL_STORAGE_FAILURE(0x0F);

    companion object {
        /** Returns null for unknown status bytes (fail-closed: caller must handle). */
        fun fromByte(value: Byte): StatusCode? = entries.find { it.code == value }
    }
}
