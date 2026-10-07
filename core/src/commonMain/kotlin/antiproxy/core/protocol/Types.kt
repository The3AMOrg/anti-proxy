package antiproxy.core.protocol

/** Protocol version byte. */
const val PROTOCOL_VERSION: Byte = 0x01

// Wire format sizes
const val SESSION_SIGNED_SIZE = 133
const val SESSION_BODY_SIZE = 69   // Bytes signed by authority (everything before signature)
const val PROOF_SIZE = 57
const val PROOF_ENVELOPE_SIZE = 121
const val SIGNATURE_SIZE = 64
const val KEY_ID_SIZE = 16
const val SESSION_ID_SIZE = 16
const val NONCE_SIZE = 16

// BLE UUIDs
const val SERVICE_UUID_STRING = "9de3c1f4-6187-48dd-8d05-976a94a9cdd7"
const val SESSION_CHAR_UUID_STRING = "f0ff0423-5422-495a-a68b-917b963125b4"
const val PROOF_CHAR_UUID_STRING = "5262b34c-79b9-472b-9beb-652aa023d3ce"
const val STATUS_CHAR_UUID_STRING = "63c56110-34fa-4d02-958e-54c4cb940ec8"

// Timing constants
const val DEFAULT_VERIFICATION_WINDOW_SECONDS = 20
const val SLOT_DURATION_MS = 100L
const val NUM_SLOTS = 200
const val MAX_CLOCK_SKEW_SECONDS = 30L
const val DEFAULT_ADVERTISING_INTERVAL_MS = 200
const val MIN_RSSI_SAMPLES = 10

// Fragment limits
const val MAX_FRAGMENT_TOTAL_LENGTH = 512  // Well above 121-byte ProofEnvelope
const val MAX_FRAGMENT_CHUNKS = 16
const val FRAGMENT_HEADER_SIZE = 6  // transfer_id(2) + seq(1) + total(1) + chunk_len(2)
const val FRAGMENT_TRANSFER_TIMEOUT_MS = 5000L

// Retry limits
const val MAX_RETRY_COUNT = 5
const val RETRY_MAX_SLOTS = 64
const val RETRY_JITTER_BOUND = 10

/**
 * Unsigned session representation. Contains the session fields
 * that are canonically encoded and signed by the Authority.
 */
data class Session(
    val authorityKeyId: ByteArray,    // 16 bytes
    val sessionId: ByteArray,         // 16 bytes
    val nonce: ByteArray,             // 16 bytes
    val issuedAt: Long,               // UTC Unix seconds
    val expiresAt: Long,              // UTC Unix seconds
    val policyId: Int                 // uint32 (stored as Int, interpreted unsigned)
) {
    init {
        require(authorityKeyId.size == KEY_ID_SIZE) { "authorityKeyId must be $KEY_ID_SIZE bytes" }
        require(sessionId.size == SESSION_ID_SIZE) { "sessionId must be $SESSION_ID_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        require(issuedAt >= 0) { "issuedAt must be non-negative" }
        require(expiresAt >= issuedAt) { "expiresAt must be >= issuedAt" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Session) return false
        return authorityKeyId.contentEquals(other.authorityKeyId) &&
            sessionId.contentEquals(other.sessionId) &&
            nonce.contentEquals(other.nonce) &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            policyId == other.policyId
    }

    override fun hashCode(): Int {
        var result = authorityKeyId.contentHashCode()
        result = 31 * result + sessionId.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + policyId
        return result
    }
}

/**
 * Session with Authority's ECDSA-P256-SHA256 signature.
 * Wire format: 133 bytes total.
 */
data class SessionSigned(
    val session: Session,
    val authoritySignature: ByteArray  // 64 bytes (r[32] || s[32])
) {
    init {
        require(authoritySignature.size == SIGNATURE_SIZE) { "signature must be $SIGNATURE_SIZE bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionSigned) return false
        return session == other.session && authoritySignature.contentEquals(other.authoritySignature)
    }

    override fun hashCode(): Int = 31 * session.hashCode() + authoritySignature.contentHashCode()
}

/**
 * Student proof of attendance. Canonical byte layout is signed.
 * Wire format: 57 bytes.
 */
data class Proof(
    val protocolVersion: Byte,
    val sessionId: ByteArray,         // 16 bytes
    val nonce: ByteArray,             // 16 bytes
    val deviceKeyId: ByteArray,       // 16 bytes
    val rssiMeanQ1: Byte,             // signed Q1 fixed-point dBm
    val rssiVarianceQ8_8: UShort,     // unsigned Q8.8 fixed-point dBm²
    val sampleCount: UShort,          // number of RSSI samples
    val proofFlags: Byte              // reserved flags
) {
    init {
        require(sessionId.size == SESSION_ID_SIZE) { "sessionId must be $SESSION_ID_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        require(deviceKeyId.size == KEY_ID_SIZE) { "deviceKeyId must be $KEY_ID_SIZE bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Proof) return false
        return protocolVersion == other.protocolVersion &&
            sessionId.contentEquals(other.sessionId) &&
            nonce.contentEquals(other.nonce) &&
            deviceKeyId.contentEquals(other.deviceKeyId) &&
            rssiMeanQ1 == other.rssiMeanQ1 &&
            rssiVarianceQ8_8 == other.rssiVarianceQ8_8 &&
            sampleCount == other.sampleCount &&
            proofFlags == other.proofFlags
    }

    override fun hashCode(): Int {
        var result = protocolVersion.hashCode()
        result = 31 * result + sessionId.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + deviceKeyId.contentHashCode()
        result = 31 * result + rssiMeanQ1.hashCode()
        result = 31 * result + rssiVarianceQ8_8.hashCode()
        result = 31 * result + sampleCount.hashCode()
        result = 31 * result + proofFlags.hashCode()
        return result
    }
}

/**
 * ProofEnvelope = Proof bytes || student ECDSA-P256-SHA256 signature.
 * Wire format: 121 bytes.
 */
data class ProofEnvelope(
    val proof: Proof,
    val studentSignature: ByteArray  // 64 bytes (r[32] || s[32])
) {
    init {
        require(studentSignature.size == SIGNATURE_SIZE) { "signature must be $SIGNATURE_SIZE bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProofEnvelope) return false
        return proof == other.proof && studentSignature.contentEquals(other.studentSignature)
    }

    override fun hashCode(): Int = 31 * proof.hashCode() + studentSignature.contentHashCode()
}

/** BLE GATT fragment for proof transfer. */
data class Fragment(
    val transferId: UShort,
    val sequenceNumber: Byte,
    val totalChunks: Byte,
    val chunkLength: UShort,
    val payload: ByteArray
) {
    init {
        require(payload.size == chunkLength.toInt()) { "payload size must match chunkLength" }
        require(totalChunks.toInt() in 1..MAX_FRAGMENT_CHUNKS) { "totalChunks out of range" }
        require(sequenceNumber.toInt() in 0 until totalChunks.toInt()) { "sequenceNumber out of range" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Fragment) return false
        return transferId == other.transferId &&
            sequenceNumber == other.sequenceNumber &&
            totalChunks == other.totalChunks &&
            chunkLength == other.chunkLength &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + totalChunks.hashCode()
        result = 31 * result + chunkLength.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
