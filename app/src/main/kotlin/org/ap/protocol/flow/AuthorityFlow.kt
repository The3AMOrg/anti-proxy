package org.ap.protocol.flow

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.*
import org.ap.protocol.ble.AuthorityAdvertiser
import org.ap.protocol.ble.ProofReceivedCallback
import org.ap.protocol.ble.beaconIdOf
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.storage.AndroidAuthorityStore
import antiproxy.core.attendance.*
import antiproxy.core.crypto.*
import antiproxy.core.integration.*
import antiproxy.core.protocol.*
import antiproxy.core.storage.*
import java.security.SecureRandom
import java.util.UUID

/**
 * Orchestrates the Authority-side verification flow:
 * 1. Validates offline trust lease & clock rollback guard
 * 2. Creates and cryptographically signs Session
 * 3. Starts BLE GATT server & advertising
 * 4. Reassembles incoming student proof fragments
 * 5. Executes 13-step strict validation order
 * 6. Durably commits attendance decision to SQLite WAL before sending ACK
 * 7. Returns compact Status ACK / rejection code
 */
class AuthorityFlow(
    private val context: Context,
    private val keyManager: AndroidKeyManager,
    private val keyAlias: String,
    private val authorityStore: AndroidAuthorityStore,
    private val rssiPolicy: RssiPolicy = RssiPolicy.DEFAULT,
    private val verifier: Verifier = keyManager.createVerifier(),
    val sessionDurationSeconds: Int = 300 // 5 minutes default
) : AuthorityApi {

    @Volatile
    private var currentSession: Session? = null

    @Volatile
    private var sessionState: SessionState = SessionState.IDLE

    private var advertiser: AuthorityAdvertiser? = null
    private var sessionScope: CoroutineScope? = null
    private val secureRandom = SecureRandom()

    var onAttendanceRecorded: ((record: AttendanceRecord?, status: StatusCode) -> Unit)? = null

    override fun getSessionState(): SessionState = sessionState

    override fun getActiveSession(): Session? = currentSession

    override fun getAttendanceCount(): Int {
        val session = currentSession ?: return 0
        return authorityStore.getAttendanceCount(session.sessionId)
    }

    override suspend fun startSession(): Session {
        check(sessionState == SessionState.IDLE || sessionState == SessionState.ERROR) {
            "Cannot start session in state: $sessionState"
        }
        sessionState = SessionState.CREATING

        val nowSeconds = System.currentTimeMillis() / 1000

        // 1. Validate offline trust lease
        val lease = authorityStore.getTrustLease()
        if (lease != null) {
            when (validateTrustLease(lease, nowSeconds)) {
                is TrustLeaseStatus.Expired -> {
                    sessionState = SessionState.ERROR
                    error("Trust lease has expired")
                }
                is TrustLeaseStatus.NotYetValid -> {
                    sessionState = SessionState.ERROR
                    error("Trust lease is not yet valid")
                }
                is TrustLeaseStatus.SignatureInvalid -> {
                    sessionState = SessionState.ERROR
                    error("Trust lease signature is invalid")
                }
                TrustLeaseStatus.Valid -> {
                    // Lease valid
                }
            }
        }

        // 2. Wall-clock rollback check
        val clockGuard = ClockGuard(
            loadLastTime = { authorityStore.getLastKnownTime() },
            saveLastTime = { authorityStore.setLastKnownTime(it) }
        )
        if (!clockGuard.checkAndUpdate(nowSeconds)) {
            sessionState = SessionState.ERROR
            error("Clock rollback detected")
        }

        // 3. Generate cryptographic session fields
        val publicKey = keyManager.getPublicKey(keyAlias) ?: error("Authority key not found: $keyAlias")
        val authorityKeyId = computeKeyId(publicKey)

        val sessionId = ByteArray(SESSION_ID_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }

        val session = Session(
            authorityKeyId = authorityKeyId.bytes,
            sessionId = sessionId,
            nonce = nonce,
            issuedAt = nowSeconds,
            expiresAt = nowSeconds + sessionDurationSeconds,
            policyId = rssiPolicy.policyVersion
        )

        // 4. Sign session
        val sessionBody = encodeSessionBody(session)
        val signer = keyManager.createSigner(keyAlias)
        val signature = signer.sign(sessionBody)
        val signedSession = SessionSigned(session, signature.bytes)
        val signedBytes = encodeSessionSigned(signedSession)

        // 5. Persist session
        authorityStore.saveSession(session)
        currentSession = session

        // 6. Begin BLE Advertising & GATT Server
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.sessionScope = scope

        val adv = AuthorityAdvertiser(context)
        adv.start(signedBytes, beaconIdOf(authorityKeyId.bytes), ProofReceivedCallback { device, envelopeData ->
            handleProofReceived(device, envelopeData)
        })

        // Periodic reaper to reclaim stale central connections (e.g. crashed students)
        scope.launch {
            while (isActive) {
                delay(2000L)
                adv.cleanupStaleConnections(5000L)
            }
        }

        this.advertiser = adv
        sessionState = SessionState.ACTIVE

        return session
    }

    override suspend fun stopSession() {
        sessionState = SessionState.STOPPING
        sessionScope?.cancel()
        sessionScope = null
        advertiser?.stop()
        advertiser = null
        currentSession = null
        sessionState = SessionState.IDLE
    }

    private fun handleProofReceived(device: BluetoothDevice, envelopeData: ByteArray) {
        val session = currentSession ?: return
        val nowSeconds = System.currentTimeMillis() / 1000

        // Strict 13-step verification order
        val status = validateProofEnvelope(
            envelopeData = envelopeData,
            sessionLookup = { sid -> authorityStore.getSession(sid) },
            // Only members of the class being taken, not every class this teacher has
            deviceKeyLookup = { kid -> authorityStore.getStudentKey(kid)?.takeIf { AttendanceRoster.allows(kid) } },
            deviceStatusCheck = { kid ->
                when {
                    authorityStore.isDeviceRevoked(kid) -> DeviceStatus.REVOKED
                    authorityStore.getStudentKey(kid) != null -> DeviceStatus.ACTIVE
                    else -> DeviceStatus.UNKNOWN
                }
            },
            verifier = verifier,
            rssiPolicy = rssiPolicy,
            replayCheck = { sid, kid -> authorityStore.hasAttendance(sid, kid) },
            currentTimeSeconds = nowSeconds
        )

        var recordedRecord: AttendanceRecord? = null

        if (status == StatusCode.SUCCESS) {
            val envelope = try {
                decodeProofEnvelope(envelopeData)
            } catch (_: Exception) {
                advertiser?.sendStatus(device, StatusCode.MALFORMED_FRAME)
                onAttendanceRecorded?.invoke(null, StatusCode.MALFORMED_FRAME)
                disconnectAfterAck(device)
                return
            }

            val record = AttendanceRecord(
                sessionId = envelope.proof.sessionId,
                deviceKeyId = envelope.proof.deviceKeyId,
                authorityKeyId = session.authorityKeyId,
                policyId = session.policyId,
                policyVersion = rssiPolicy.policyVersion,
                rssiMean = fromQ1(envelope.proof.rssiMeanQ1),
                rssiVariance = fromQ8_8(envelope.proof.rssiVarianceQ8_8),
                sampleCount = envelope.proof.sampleCount.toInt(),
                recordedAt = nowSeconds,
                idempotencyKey = computeIdempotencyKey(
                    authorityDeviceId = session.authorityKeyId,
                    sessionId = session.sessionId,
                    studentDeviceKeyId = envelope.proof.deviceKeyId
                )
            )

            val syncEvent = SyncEvent(
                eventId = UUID.randomUUID().toString(),
                payload = envelopeData,
                idempotencyKey = record.idempotencyKey,
                createdAt = nowSeconds,
                syncState = SyncState.PENDING,
                retryCount = 0,
                nextAttemptAt = null,
                lastError = null
            )

            val committed = authorityStore.commitAttendance(record, syncEvent)
            if (!committed) {
                advertiser?.sendStatus(device, StatusCode.INTERNAL_STORAGE_FAILURE)
                onAttendanceRecorded?.invoke(null, StatusCode.INTERNAL_STORAGE_FAILURE)
                disconnectAfterAck(device)
                return
            }
            recordedRecord = record
        }

        advertiser?.sendStatus(device, status)
        onAttendanceRecorded?.invoke(recordedRecord, status)
        disconnectAfterAck(device)
    }

    private fun disconnectAfterAck(device: BluetoothDevice) {
        val scope = sessionScope
        if (scope != null) {
            scope.launch {
                delay(200L) // Wait 200ms for status indication ATT confirmation over air
                advertiser?.disconnectClient(device)
            }
        } else {
            advertiser?.disconnectClient(device)
        }
    }
}
