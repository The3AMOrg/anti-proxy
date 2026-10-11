package org.ap.protocol.flow

import android.content.Context
import org.ap.protocol.ble.*
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.storage.AndroidStudentStore
import antiproxy.core.attendance.*
import antiproxy.core.crypto.*
import antiproxy.core.integration.*
import antiproxy.core.protocol.*
import antiproxy.core.scheduling.*
import antiproxy.core.storage.*
import kotlinx.coroutines.delay
import java.util.UUID

sealed class StudentCheckInState {
    object Idle : StudentCheckInState()
    object Scanning : StudentCheckInState()
    data class Discovered(val address: String, val sampleCount: Int, val meanRssi: Double) : StudentCheckInState()
    object Connecting : StudentCheckInState()
    object VerifyingSession : StudentCheckInState()
    object SigningProof : StudentCheckInState()
    object Submitting : StudentCheckInState()
    data class Success(val record: AttendanceRecord) : StudentCheckInState()
    data class Rejected(val status: StatusCode, val reason: String) : StudentCheckInState()
    data class Error(val message: String, val cause: Throwable? = null) : StudentCheckInState()
}

/**
 * Orchestrates the complete student attendance flow with pre-connection RSSI sampling
 * and ultra-ephemeral GATT connection.
 */
class StudentFlow(
    private val context: Context,
    private val keyManager: AndroidKeyManager,
    private val keyAlias: String,
    private val studentStore: AndroidStudentStore,
    private val knownAuthorityKeys: Map<KeyId, PublicKeyBytes>,
    /** The class being checked in to, saved with the record so its history stays its own. */
    private val classroomId: String? = null,
    private val verifier: Verifier = keyManager.createVerifier()
) : StudentApi {

    var onStateChanged: ((StudentCheckInState) -> Unit)? = null

    override fun getEnrollmentState(): EnrollmentState = studentStore.getEnrollmentState()

    override fun getAttendanceHistory(limit: Int): List<AttendanceRecord> =
        studentStore.getAttendanceHistory(limit)

    override fun getLastSyncTime(): Long? = studentStore.getLastSyncTime()

    override suspend fun performAttendance(): AttendanceResult {
        val enrollment = studentStore.getEnrollmentState()
        if (!enrollment.isEnrolled || enrollment.deviceKeyId == null) {
            val err = "Device is not enrolled"
            onStateChanged?.invoke(StudentCheckInState.Error(err))
            return AttendanceResult.Error(err)
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val clockGuard = ClockGuard(
            loadLastTime = { studentStore.getLastKnownTime() },
            saveLastTime = { studentStore.setLastKnownTime(it) }
        )
        if (!clockGuard.checkAndUpdate(nowSeconds)) {
            val err = "Clock rollback detected"
            onStateChanged?.invoke(StudentCheckInState.Error(err))
            return AttendanceResult.Error(err)
        }

        onStateChanged?.invoke(StudentCheckInState.Scanning)

        // 1. Pre-connection passive scan & RSSI sampling
        val discovered = scanForAuthority(
            context,
            beaconIds = knownAuthorityKeys.keys.map { beaconIdOf(it.bytes) },
            requiredSamples = MIN_RSSI_SAMPLES
        )
        if (discovered == null) {
            onStateChanged?.invoke(StudentCheckInState.Error("No Authority beacon found"))
            return AttendanceResult.NoAuthorityFound
        }

        val mean = rssiMean(discovered.rssiSamples)
        onStateChanged?.invoke(StudentCheckInState.Discovered(discovered.deviceAddress, discovered.rssiSamples.size, mean))

        // 2. Randomized slot scheduling to spread out connection bursts
        val slot = SlotScheduler.pickSlot()
        val slotDelay = SlotScheduler.slotDelayMs(slot)
        delay(slotDelay)

        var retryNumber = 0

        while (true) {
            val client = StudentGattClient(context)
            try {
                return executeVerification(client, discovered, enrollment)
            } catch (e: GattTransportException) {
                client.disconnect()
                if (!RetryScheduler.canRetry(retryNumber)) {
                    val err = "Attendance failed after retries: ${e.message}"
                    onStateChanged?.invoke(StudentCheckInState.Error(err, e))
                    return AttendanceResult.Error(err, e)
                }
                val retryDelay = RetryScheduler.computeDelayMs(retryNumber)
                if (retryDelay == null) {
                    val err = "Max retries exceeded: ${e.message}"
                    onStateChanged?.invoke(StudentCheckInState.Error(err, e))
                    return AttendanceResult.Error(err, e)
                }
                retryNumber++
                delay(retryDelay)
            } catch (e: Exception) {
                client.disconnect()
                val err = "Attendance failed: ${e.message}"
                onStateChanged?.invoke(StudentCheckInState.Error(err, e))
                return AttendanceResult.Error(err, e)
            }
        }
    }

    private suspend fun executeVerification(
        client: StudentGattClient,
        discovered: DiscoveredAuthority,
        enrollment: EnrollmentState
    ): AttendanceResult {
        onStateChanged?.invoke(StudentCheckInState.Connecting)

        if (!client.connect(discovered.deviceAddress)) {
            throw GattTransportException("Failed to connect to Authority")
        }

        client.requestMtu()

        if (!client.discoverServices()) {
            throw GattTransportException("Failed to discover services")
        }

        onStateChanged?.invoke(StudentCheckInState.VerifyingSession)

        val sessionBytes = try {
            client.readSession()
        } catch (e: Exception) {
            throw GattTransportException("Failed to read session: ${e.message}", e)
        }
        if (sessionBytes.size != SESSION_SIGNED_SIZE) {
            throw GattTransportException("Invalid session byte size: ${sessionBytes.size}")
        }

        val signedSession = try {
            decodeSessionSigned(sessionBytes)
        } catch (e: Exception) {
            throw GattTransportException("Failed to decode session: ${e.message}", e)
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val validationResult = validateSession(
            signedSession = signedSession,
            knownAuthorityKeys = knownAuthorityKeys,
            verifier = verifier,
            currentTimeSeconds = nowSeconds
        )

        when (validationResult) {
            is SessionValidationResult.Failed -> {
                client.disconnect()
                onStateChanged?.invoke(StudentCheckInState.Rejected(validationResult.status, validationResult.reason))
                return AttendanceResult.Rejected(validationResult.status, validationResult.reason)
            }
            is SessionValidationResult.Valid -> {
                // Session authenticated
            }
        }

        val session = (validationResult as SessionValidationResult.Valid).session

        onStateChanged?.invoke(StudentCheckInState.SigningProof)

        // Use pre-collected RSSI samples gathered during broadcast scan
        val rssiSamples = discovered.rssiSamples.toMutableList()
        while (rssiSamples.size < MIN_RSSI_SAMPLES) {
            rssiSamples.add(discovered.initialRssi)
        }

        val signer = keyManager.createSigner(keyAlias)
        val rawKeyId = enrollment.deviceKeyId ?: error("Device key ID missing from enrollment")
        val deviceKeyId = KeyId(rawKeyId)

        val proofEnvelope = buildProofEnvelope(
            session = session,
            deviceKeyId = deviceKeyId,
            rssiSamples = rssiSamples,
            signer = signer
        )

        val envelopeBytes = encodeProofEnvelope(proofEnvelope)

        if (!client.enableStatusIndications()) {
            throw GattTransportException("Failed to enable status indications")
        }

        onStateChanged?.invoke(StudentCheckInState.Submitting)

        try {
            client.writeProofEnvelope(envelopeBytes)
        } catch (e: Exception) {
            throw GattTransportException("Failed to write proof envelope: ${e.message}", e)
        }

        val statusCode = try {
            client.awaitStatusIndication()
        } catch (e: Exception) {
            throw GattTransportException("Timeout or error waiting for status indication: ${e.message}", e)
        } finally {
            client.disconnect()
        }

        if (statusCode == StatusCode.SUCCESS) {
            val record = AttendanceRecord(
                sessionId = session.sessionId,
                deviceKeyId = deviceKeyId.bytes,
                authorityKeyId = session.authorityKeyId,
                policyId = session.policyId,
                policyVersion = 1,
                rssiMean = rssiMean(rssiSamples),
                rssiVariance = rssiVariance(rssiSamples),
                sampleCount = rssiSamples.size,
                recordedAt = nowSeconds,
                idempotencyKey = computeIdempotencyKey(
                    authorityDeviceId = session.authorityKeyId,
                    sessionId = session.sessionId,
                    studentDeviceKeyId = deviceKeyId.bytes
                ),
                classroomId = classroomId
            )

            val syncEvent = SyncEvent(
                eventId = UUID.randomUUID().toString(),
                payload = envelopeBytes,
                idempotencyKey = record.idempotencyKey,
                createdAt = nowSeconds,
                syncState = SyncState.PENDING,
                retryCount = 0,
                nextAttemptAt = null,
                lastError = null
            )

            studentStore.recordAttendance(record, syncEvent)
            onStateChanged?.invoke(StudentCheckInState.Success(record))
            return AttendanceResult.Success(record)
        } else if (statusCode == StatusCode.MTU_OR_FRAGMENT_ERROR) {
            throw GattTransportException("Fragment transmission error: ${statusCode.name}")
        } else {
            val reason = "Rejected by Authority: ${statusCode.name}"
            onStateChanged?.invoke(StudentCheckInState.Rejected(statusCode, reason))
            return AttendanceResult.Rejected(statusCode, reason)
        }
    }
}
