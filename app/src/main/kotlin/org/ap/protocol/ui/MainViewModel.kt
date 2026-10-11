package org.ap.protocol.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.computeKeyId
import antiproxy.core.storage.AttendanceRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.flow.StudentCheckInState
import org.ap.protocol.flow.StudentFlow
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.keystore.KeyAliases
import org.ap.protocol.service.AuthorityGattService
import org.ap.protocol.service.AuthorityServiceState
import org.ap.protocol.storage.AndroidStudentStore

/** Attendance sessions (instructor) and check-in (student). Keys are exchanged by ClassroomViewModel. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val keyManager = AndroidKeyManager(app)
    private val studentStore = AndroidStudentStore(app)

    private val _session = MutableStateFlow(AuthorityServiceState())
    val session: StateFlow<AuthorityServiceState> = _session.asStateFlow()

    private val _checkIn = MutableStateFlow<StudentCheckInState>(StudentCheckInState.Idle)
    val checkIn: StateFlow<StudentCheckInState> = _checkIn.asStateFlow()

    private val _history = MutableStateFlow<List<AttendanceRecord>>(emptyList())
    val history: StateFlow<List<AttendanceRecord>> = _history.asStateFlow()

    private var serviceJob: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AuthorityGattService.LocalBinder)?.getService() ?: return
            serviceJob = viewModelScope.launch { service.state.collect { _session.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceJob?.cancel()
        }
    }

    init {
        app.bindService(Intent(app, AuthorityGattService::class.java), connection, Context.BIND_AUTO_CREATE)
        // The device key must exist before joining a classroom, which publishes it
        if (!keyManager.hasKey(KeyAliases.STUDENT)) {
            val gen = keyManager.generateKey(KeyAliases.STUDENT)
            studentStore.saveEnrollment(gen.keyId, gen.securityLevel, System.currentTimeMillis() / 1000)
        }
        refreshHistory()
    }

    override fun onCleared() {
        getApplication<Application>().unbindService(connection)
    }

    fun startSession() {
        if (!keyManager.hasKey(KeyAliases.AUTHORITY)) keyManager.generateKey(KeyAliases.AUTHORITY)
        AuthorityGattService.start(getApplication(), KeyAliases.AUTHORITY, durationSeconds = 300)
    }

    fun stopSession() = AuthorityGattService.stop(getApplication())

    /** Checks in to one class: only that class's teacher is trusted, so a beacon next door is ignored. */
    fun checkIn(classroom: Classroom) {
        if (_checkIn.value.isBusy) return
        val classKey = runCatching { computeKeyId(PublicKeyBytes(Base64.getDecoder().decode(classroom.authorityPublicKey))) }.getOrNull()
        val instructors = studentStore.getTrustedAuthorityKeys()
            .filterKeys { classKey != null && it.bytes.contentEquals(classKey.bytes) }
        if (instructors.isEmpty()) {
            _checkIn.value = StudentCheckInState.Error("This phone doesn't have ${classroom.teacherName}'s key yet. Open AntiProxy while online and try again.")
            return
        }
        val flow = StudentFlow(
            context = getApplication(),
            keyManager = keyManager,
            keyAlias = KeyAliases.STUDENT,
            studentStore = studentStore,
            knownAuthorityKeys = instructors,
            classroomId = classroom.id
        )
        flow.onStateChanged = { _checkIn.value = it }
        _checkIn.value = StudentCheckInState.Scanning
        viewModelScope.launch {
            try {
                flow.performAttendance()
            } catch (e: Exception) {
                _checkIn.value = StudentCheckInState.Error(e.message ?: "Check-in failed.", e)
            }
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        // Enough for each class's "Your attendance" list after filtering by class
        _history.value = studentStore.getAttendanceHistory(100)
    }
}

val StudentCheckInState.isBusy: Boolean
    get() = when (this) {
        StudentCheckInState.Scanning,
        is StudentCheckInState.Discovered,
        StudentCheckInState.Connecting,
        StudentCheckInState.VerifyingSession,
        StudentCheckInState.SigningProof,
        StudentCheckInState.Submitting -> true
        else -> false
    }

fun ByteArray.shortHex(): String = take(8).joinToString("") { "%02X".format(it) }
