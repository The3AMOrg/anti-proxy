package org.ap.protocol.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.ap.protocol.flow.AuthorityFlow
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.storage.AndroidAuthorityStore
import antiproxy.core.attendance.RssiPolicy
import antiproxy.core.protocol.Session
import antiproxy.core.storage.AttendanceRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthorityServiceState(
    val isRunning: Boolean = false,
    val activeSession: Session? = null,
    val attendanceCount: Int = 0,
    val recentRecords: List<AttendanceRecord> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Android Foreground Service managing the Authority BLE GATT Server and Advertiser.
 * Holds a partial WakeLock to prevent Doze Mode from suspending the Bluetooth stack.
 */
class AuthorityGattService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: PowerManager.WakeLock? = null
    private var authorityFlow: AuthorityFlow? = null

    private val _state = MutableStateFlow(AuthorityServiceState())
    val state: StateFlow<AuthorityServiceState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): AuthorityGattService = this@AuthorityGattService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AntiProxy:AuthorityGattWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val keyAlias = intent.getStringExtra(EXTRA_KEY_ALIAS) ?: "antiproxy_authority_key"
                val duration = intent.getIntExtra(EXTRA_DURATION, 300)
                startSession(keyAlias, duration)
            }
            ACTION_STOP -> {
                stopSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun startSession(keyAlias: String, durationSeconds: Int = 300) {
        if (_state.value.isRunning) return

        wakeLock?.acquire(durationSeconds * 1000L + 30_000L)

        val notification = buildNotification("Starting AntiProxy Attendance Session...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val keyManager = AndroidKeyManager(this)
        val authorityStore = AndroidAuthorityStore(this)
        val flow = AuthorityFlow(
            context = this,
            keyManager = keyManager,
            keyAlias = keyAlias,
            authorityStore = authorityStore,
            rssiPolicy = RssiPolicy.DEFAULT,
            sessionDurationSeconds = durationSeconds
        )

        flow.onAttendanceRecorded = { record, status ->
            val current = _state.value
            val updatedRecords = if (record != null) {
                listOf(record) + current.recentRecords.take(49)
            } else {
                current.recentRecords
            }
            val count = flow.getAttendanceCount()
            _state.value = current.copy(
                attendanceCount = count,
                recentRecords = updatedRecords
            )
            updateNotification("Active Attendance: $count students verified")
        }

        this.authorityFlow = flow

        scope.launch {
            try {
                val session = flow.startSession()
                _state.value = AuthorityServiceState(
                    isRunning = true,
                    activeSession = session,
                    attendanceCount = 0,
                    recentRecords = emptyList()
                )
                updateNotification("Active Attendance: 0 students verified")
            } catch (e: Exception) {
                _state.value = AuthorityServiceState(
                    isRunning = false,
                    errorMessage = e.message
                )
                stopSession()
            }
        }
    }

    fun stopSession() {
        scope.launch {
            try {
                authorityFlow?.stopSession()
            } catch (_: Exception) { }
            authorityFlow = null
            _state.value = _state.value.copy(isRunning = false, activeSession = null)

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) { }

            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AntiProxy Authority Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE GATT server running for physical attendance verification"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiProxy Verification Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "antiproxy_authority_service"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "org.ap.protocol.service.START"
        const val ACTION_STOP = "org.ap.protocol.service.STOP"
        const val EXTRA_KEY_ALIAS = "extra_key_alias"
        const val EXTRA_DURATION = "extra_duration"

        fun start(context: Context, keyAlias: String, durationSeconds: Int = 300) {
            val intent = Intent(context, AuthorityGattService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_KEY_ALIAS, keyAlias)
                putExtra(EXTRA_DURATION, durationSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AuthorityGattService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
