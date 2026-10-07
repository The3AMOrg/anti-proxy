package org.ap.protocol.ui

import android.Manifest
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.ap.protocol.flow.StudentCheckInState
import org.ap.protocol.flow.StudentFlow
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.service.AuthorityGattService
import org.ap.protocol.storage.AndroidAuthorityStore
import org.ap.protocol.storage.AndroidStudentStore
import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.integration.AttendanceResult
import antiproxy.core.storage.AttendanceRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var keyManager: AndroidKeyManager
    private lateinit var authorityStore: AndroidAuthorityStore
    private lateinit var studentStore: AndroidStudentStore

    private var authorityService: AuthorityGattService? = null
    private var isServiceBound = false

    private val authorityKeyAlias = "antiproxy_authority_key"
    private val studentKeyAlias = "antiproxy_device_signing_key"

    // UI Elements
    private lateinit var roleToggleGroup: LinearLayout
    private lateinit var authorityBtn: Button
    private lateinit var studentBtn: Button
    private lateinit var authorityContainer: LinearLayout
    private lateinit var studentContainer: LinearLayout

    // Authority UI
    private lateinit var authSessionStatusText: TextView
    private lateinit var authCountText: TextView
    private lateinit var authStartBtn: Button
    private lateinit var authStopBtn: Button
    private lateinit var authKeyDetailsText: TextView
    private lateinit var authRosterContainer: LinearLayout

    // Student UI
    private lateinit var studentKeyStatusText: TextView
    private lateinit var studentCheckInBtn: Button
    private lateinit var studentProgressText: TextView
    private lateinit var studentProgressBar: ProgressBar
    private lateinit var studentHistoryContainer: LinearLayout
    private lateinit var studentAuthorityStatusText: TextView

    private var currentRole = Role.AUTHORITY

    private enum class Role { AUTHORITY, STUDENT }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AuthorityGattService.LocalBinder
            authorityService = localBinder?.getService()
            isServiceBound = true
            observeAuthorityService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            authorityService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("AntiProxy", "MainActivity.onCreate started")

        try {
            keyManager = AndroidKeyManager(this)
            authorityStore = AndroidAuthorityStore(this)
            studentStore = AndroidStudentStore(this)

            setContentView(buildMainLayout())

            checkAndRequestPermissions()

            try {
                val intent = Intent(this, AuthorityGattService::class.java)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e("AntiProxy", "Error binding AuthorityGattService", e)
            }

            try {
                refreshAuthorityKeys()
            } catch (e: Exception) {
                Log.e("AntiProxy", "Error refreshing authority keys", e)
            }

            try {
                refreshStudentKeys()
            } catch (e: Exception) {
                Log.e("AntiProxy", "Error refreshing student keys", e)
            }

            try {
                refreshStudentHistory()
            } catch (e: Exception) {
                Log.e("AntiProxy", "Error refreshing student history", e)
            }
        } catch (t: Throwable) {
            Log.e("AntiProxy", "Fatal initialization error in MainActivity.onCreate", t)
            Toast.makeText(this, "Startup error: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun observeAuthorityService() {
        val service = authorityService ?: return
        scope.launch {
            service.state.collectLatest { state ->
                authSessionStatusText.text = if (state.isRunning) {
                    "● ACTIVE SESSION (Broadcasting BLE)"
                } else {
                    "○ SESSION IDLE"
                }
                authSessionStatusText.setTextColor(if (state.isRunning) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))

                authCountText.text = "${state.attendanceCount}"
                authStartBtn.isEnabled = !state.isRunning
                authStopBtn.isEnabled = state.isRunning

                updateRosterList(state.recentRecords)
            }
        }
    }

    private fun updateRosterList(records: List<AttendanceRecord>) {
        authRosterContainer.removeAllViews()
        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No students verified yet this session."
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 12, 0, 12)
            }
            authRosterContainer.addView(empty)
            return
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        records.forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                background = cardBackground(Color.parseColor("#F5F5F5"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 8
                layoutParams = lp
            }

            val title = TextView(this).apply {
                text = "Student: ${record.deviceKeyId.take(8).joinToString("") { "%02X".format(it) }}..."
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            }

            val details = TextView(this).apply {
                val timeStr = sdf.format(Date(record.recordedAt * 1000))
                text = "Time: $timeStr | RSSI: ${"%.1f".format(record.rssiMean)} dBm (σ²=${"%.1f".format(record.rssiVariance)}) | N=${record.sampleCount}"
                textSize = 12f
                setTextColor(Color.parseColor("#616161"))
            }

            row.addView(title)
            row.addView(details)
            authRosterContainer.addView(row)
        }
    }

    private fun buildMainLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 40)
        }

        // Header
        val header = TextView(this).apply {
            text = "AntiProxy"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
        }

        val subtitle = TextView(this).apply {
            text = "Ultra-Fast Offline BLE Attendance Verification"
            textSize = 14f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 4, 0, 24)
        }

        // Role Switcher Tabs
        roleToggleGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            background = cardBackground(Color.parseColor("#E8EAED"))
        }

        authorityBtn = Button(this).apply {
            text = "Instructor / Authority"
            isSelected = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchRole(Role.AUTHORITY) }
        }

        studentBtn = Button(this).apply {
            text = "Student Node"
            isSelected = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchRole(Role.STUDENT) }
        }

        roleToggleGroup.addView(authorityBtn)
        roleToggleGroup.addView(studentBtn)

        // Subcontainers
        authorityContainer = buildAuthoritySection()
        studentContainer = buildStudentSection().apply { visibility = View.GONE }

        container.addView(header)
        container.addView(subtitle)
        container.addView(roleToggleGroup)
        container.addView(space(24))
        container.addView(authorityContainer)
        container.addView(studentContainer)

        root.addView(container)
        return root
    }

    private fun switchRole(role: Role) {
        currentRole = role
        if (role == Role.AUTHORITY) {
            authorityContainer.visibility = View.VISIBLE
            studentContainer.visibility = View.GONE
            authorityBtn.setTextColor(Color.parseColor("#1A73E8"))
            studentBtn.setTextColor(Color.parseColor("#5F6368"))
        } else {
            authorityContainer.visibility = View.GONE
            studentContainer.visibility = View.VISIBLE
            authorityBtn.setTextColor(Color.parseColor("#5F6368"))
            studentBtn.setTextColor(Color.parseColor("#1A73E8"))
        }
    }

    // ==================== AUTHORITY UI ====================

    private fun buildAuthoritySection(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
        }

        authSessionStatusText = TextView(this).apply {
            text = "○ SESSION IDLE"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#9E9E9E"))
        }

        val countLabel = TextView(this).apply {
            text = "Verified Attendees"
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 12, 0, 0)
        }

        authCountText = TextView(this).apply {
            text = "0"
            textSize = 48f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        authStartBtn = Button(this).apply {
            text = "Start Session (5m)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startAuthoritySession() }
        }

        authStopBtn = Button(this).apply {
            text = "Stop Session"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { stopAuthoritySession() }
        }

        btnRow.addView(authStartBtn)
        btnRow.addView(authStopBtn)

        statusCard.addView(authSessionStatusText)
        statusCard.addView(countLabel)
        statusCard.addView(authCountText)
        statusCard.addView(btnRow)

        // Key & Provisioning Card
        val keyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 20
            layoutParams = lp
        }

        val keyTitle = TextView(this).apply {
            text = "Authority Identity & Provisioning"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        }

        authKeyDetailsText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 8, 0, 12)
        }

        val copyKeyBtn = Button(this).apply {
            text = "Copy Authority Public Key"
            setOnClickListener { copyAuthorityKeyToClipboard() }
        }

        val enrollStudentBtn = Button(this).apply {
            text = "Enroll Student Public Key"
            setOnClickListener { showEnrollStudentDialog() }
        }

        keyCard.addView(keyTitle)
        keyCard.addView(authKeyDetailsText)
        keyCard.addView(copyKeyBtn)
        keyCard.addView(enrollStudentBtn)

        // Live Roster Card
        val rosterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 20
            layoutParams = lp
        }

        val rosterTitle = TextView(this).apply {
            text = "Live Verification Feed"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, 8)
        }

        authRosterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        rosterCard.addView(rosterTitle)
        rosterCard.addView(authRosterContainer)

        root.addView(statusCard)
        root.addView(keyCard)
        root.addView(rosterCard)

        return root
    }

    private fun startAuthoritySession() {
        if (!keyManager.hasKey(authorityKeyAlias)) {
            keyManager.generateKey(authorityKeyAlias)
            refreshAuthorityKeys()
        }
        AuthorityGattService.start(this, authorityKeyAlias, durationSeconds = 300)
    }

    private fun stopAuthoritySession() {
        AuthorityGattService.stop(this)
    }

    private fun refreshAuthorityKeys() {
        if (!keyManager.hasKey(authorityKeyAlias)) {
            authKeyDetailsText.text = "No key generated yet. Tap Start Session to auto-create."
            return
        }
        val pub = keyManager.getPublicKey(authorityKeyAlias) ?: return
        val keyId = antiproxy.core.crypto.computeKeyId(pub)
        val enrolledCount = authorityStore.getAllStudentKeys().size
        authKeyDetailsText.text = "Key ID: ${keyId.toHex()}\nEnrolled Students in Roster: $enrolledCount"
    }

    private fun copyAuthorityKeyToClipboard() {
        val pub = keyManager.getPublicKey(authorityKeyAlias)
        if (pub == null) {
            Toast.makeText(this, "Generate a key first", Toast.LENGTH_SHORT).show()
            return
        }
        val b64 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(pub.bytes)
        } else {
            android.util.Base64.encodeToString(pub.bytes, android.util.Base64.NO_WRAP)
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Authority Public Key", b64))
        Toast.makeText(this, "Authority Key copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun showEnrollStudentDialog() {
        val input = EditText(this).apply {
            hint = "Paste student public key Base64"
            minLines = 3
        }

        AlertDialog.Builder(this)
            .setTitle("Enroll Student Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    try {
                        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Base64.getDecoder().decode(text)
                        } else {
                            android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                        }
                        val pub = PublicKeyBytes(raw)
                        val kid = antiproxy.core.crypto.computeKeyId(pub)
                        authorityStore.cacheStudentKey(kid, pub)
                        refreshAuthorityKeys()
                        Toast.makeText(this, "Student enrolled! Key ID: ${kid.toHex()}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Invalid key format: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================== STUDENT UI ====================

    private fun buildStudentSection(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Action Card
        val actionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
        }

        val actionTitle = TextView(this).apply {
            text = "Student Attendance Node"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        }

        studentKeyStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 4, 0, 16)
        }

        studentCheckInBtn = Button(this).apply {
            text = "Check In / Mark Attendance"
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener { performStudentCheckIn() }
        }

        studentProgressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, 16, 0, 8)
        }

        studentProgressText = TextView(this).apply {
            text = "Ready to scan"
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        actionCard.addView(actionTitle)
        actionCard.addView(studentKeyStatusText)
        actionCard.addView(studentCheckInBtn)
        actionCard.addView(studentProgressBar)
        actionCard.addView(studentProgressText)

        // Provisioning Card
        val provCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 20
            layoutParams = lp
        }

        val provTitle = TextView(this).apply {
            text = "Device Setup & Pairing"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        }

        studentAuthorityStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 4, 0, 12)
        }

        val copyStudentKeyBtn = Button(this).apply {
            text = "Copy My Public Key (For Instructor)"
            setOnClickListener { copyStudentKeyToClipboard() }
        }

        val pairAuthorityBtn = Button(this).apply {
            text = "Pair Authority Public Key"
            setOnClickListener { showPairAuthorityDialog() }
        }

        provCard.addView(provTitle)
        provCard.addView(studentAuthorityStatusText)
        provCard.addView(copyStudentKeyBtn)
        provCard.addView(pairAuthorityBtn)

        // History Card
        val histCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = cardBackground(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 20
            layoutParams = lp
        }

        val histTitle = TextView(this).apply {
            text = "Local Attendance History"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, 8)
        }

        studentHistoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        histCard.addView(histTitle)
        histCard.addView(studentHistoryContainer)

        root.addView(actionCard)
        root.addView(provCard)
        root.addView(histCard)

        return root
    }

    private fun refreshStudentKeys() {
        if (!keyManager.hasKey(studentKeyAlias)) {
            val gen = keyManager.generateKey(studentKeyAlias)
            studentStore.saveEnrollment(gen.keyId, gen.securityLevel, System.currentTimeMillis() / 1000)
        }
        val enrollment = studentStore.getEnrollmentState()
        val secLevel = enrollment.keySecurityLevel?.name ?: "UNKNOWN"
        val kidHex = enrollment.deviceKeyId?.take(8)?.joinToString("") { "%02X".format(it) } ?: "None"
        studentKeyStatusText.text = "Hardware Key ID: $kidHex... ($secLevel)"

        val knownAuth = studentStore.getTrustedAuthorityKeys()
        studentAuthorityStatusText.text = "Trusted Authorities: ${knownAuth.size} paired"
    }

    private fun copyStudentKeyToClipboard() {
        val pub = keyManager.getPublicKey(studentKeyAlias) ?: return
        val b64 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(pub.bytes)
        } else {
            android.util.Base64.encodeToString(pub.bytes, android.util.Base64.NO_WRAP)
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Student Public Key", b64))
        Toast.makeText(this, "Student Key copied! Share with instructor.", Toast.LENGTH_SHORT).show()
    }

    private fun showPairAuthorityDialog() {
        val input = EditText(this).apply {
            hint = "Paste authority public key Base64"
            minLines = 3
        }

        AlertDialog.Builder(this)
            .setTitle("Pair Authority Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    try {
                        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Base64.getDecoder().decode(text)
                        } else {
                            android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                        }
                        val pub = PublicKeyBytes(raw)
                        val kid = antiproxy.core.crypto.computeKeyId(pub)
                        studentStore.saveTrustedAuthorityKey(kid, pub)
                        refreshStudentKeys()
                        Toast.makeText(this, "Authority paired! Key ID: ${kid.toHex()}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Invalid key format: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performStudentCheckIn() {
        val knownAuth = studentStore.getTrustedAuthorityKeys()
        if (knownAuth.isEmpty()) {
            Toast.makeText(this, "Please pair an Authority Public Key first!", Toast.LENGTH_LONG).show()
            return
        }

        studentCheckInBtn.isEnabled = false
        studentProgressBar.visibility = View.VISIBLE
        studentProgressText.text = "Scanning for Authority BLE beacon..."

        val flow = StudentFlow(
            context = this,
            keyManager = keyManager,
            keyAlias = studentKeyAlias,
            studentStore = studentStore,
            knownAuthorityKeys = knownAuth
        )

        flow.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    is StudentCheckInState.Scanning -> {
                        studentProgressText.text = "Scanning broadcast channels..."
                    }
                    is StudentCheckInState.Discovered -> {
                        studentProgressText.text = "Found beacon! Sampled ${state.sampleCount} RSSI packets (Avg: ${"%.1f".format(state.meanRssi)} dBm)"
                    }
                    is StudentCheckInState.Connecting -> {
                        studentProgressText.text = "Connecting via ultra-fast GATT channel..."
                    }
                    is StudentCheckInState.VerifyingSession -> {
                        studentProgressText.text = "Verifying Authority cryptographic session..."
                    }
                    is StudentCheckInState.SigningProof -> {
                        studentProgressText.text = "Signing proof with Android Keystore..."
                    }
                    is StudentCheckInState.Submitting -> {
                        studentProgressText.text = "Submitting proof envelope & awaiting ACK..."
                    }
                    is StudentCheckInState.Success -> {
                        studentProgressText.text = "✓ Attendance Confirmed! Recorded in local ledger."
                        studentProgressText.setTextColor(Color.parseColor("#2E7D32"))
                        studentProgressBar.visibility = View.GONE
                        studentCheckInBtn.isEnabled = true
                        refreshStudentHistory()
                    }
                    is StudentCheckInState.Rejected -> {
                        studentProgressText.text = "✕ Rejected: ${state.reason}"
                        studentProgressText.setTextColor(Color.parseColor("#C62828"))
                        studentProgressBar.visibility = View.GONE
                        studentCheckInBtn.isEnabled = true
                    }
                    is StudentCheckInState.Error -> {
                        studentProgressText.text = "✕ Error: ${state.message}"
                        studentProgressText.setTextColor(Color.parseColor("#C62828"))
                        studentProgressBar.visibility = View.GONE
                        studentCheckInBtn.isEnabled = true
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            try {
                flow.performAttendance()
            } catch (e: Exception) {
                studentProgressText.text = "Failed: ${e.message}"
                studentProgressBar.visibility = View.GONE
                studentCheckInBtn.isEnabled = true
            }
        }
    }

    private fun refreshStudentHistory() {
        studentHistoryContainer.removeAllViews()
        val records = studentStore.getAttendanceHistory(10)
        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No recorded attendance yet."
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 12, 0, 12)
            }
            studentHistoryContainer.addView(empty)
            return
        }

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        records.forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                background = cardBackground(Color.parseColor("#F5F5F5"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 8
                layoutParams = lp
            }

            val title = TextView(this).apply {
                text = "Session: ${record.sessionId.take(8).joinToString("") { "%02X".format(it) }}..."
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            }

            val details = TextView(this).apply {
                val timeStr = sdf.format(Date(record.recordedAt * 1000))
                text = "$timeStr | Mean RSSI: ${"%.1f".format(record.rssiMean)} dBm"
                textSize = 12f
                setTextColor(Color.parseColor("#616161"))
            }

            row.addView(title)
            row.addView(details)
            studentHistoryContainer.addView(row)
        }
    }

    // ==================== HELPERS ====================

    private fun cardBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 16f
        }
    }

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (heightDp * resources.displayMetrics.density).toInt())
        }
    }

    private fun KeyId.toHex(): String = bytes.joinToString("") { "%02X".format(it) }
}
