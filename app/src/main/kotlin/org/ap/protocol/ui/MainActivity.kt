@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package org.ap.protocol.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.ap.protocol.account.AccountState
import org.ap.protocol.account.AccountViewModel
import org.ap.protocol.account.AppRole
import org.ap.protocol.account.ThemeMode
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.ClassroomViewModel
import org.ap.protocol.classroom.JoinCode
import org.ap.protocol.classroom.MembershipStatus
import org.ap.protocol.flow.StudentCheckInState
import org.ap.protocol.ui.theme.AntiProxyTheme
import org.ap.protocol.update.UpdateViewModel
import org.ap.protocol.update.bestApk

class MainActivity : ComponentActivity() {

    /** A join code from a tapped link; waits until the user is signed in and on the student side. */
    private var pendingJoinCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: after rotation the same intent would open the invite again
        if (savedInstanceState == null) pendingJoinCode = joinCodeFrom(intent)
        setContent {
            val account: AccountViewModel = viewModel()
            val state by account.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.System -> null
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            AntiProxyTheme(darkOverride = dark) {
                Root(account, state, joinCode = pendingJoinCode, onJoinHandled = { pendingJoinCode = null })
            }
        }
    }

    // singleTop: the app is already open when a link is tapped
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        joinCodeFrom(intent)?.let { pendingJoinCode = it }
    }

    private fun joinCodeFrom(intent: Intent?): String? =
        if (intent?.action != Intent.ACTION_VIEW) null
        else JoinCode.fromLinkPath(intent.data?.takeIf { it.host == JoinCode.LINK_HOST }?.path)
}

/** Sign in → choose a role → main app. */
@Composable
private fun Root(account: AccountViewModel, state: AccountState, joinCode: String?, onJoinHandled: () -> Unit) {
    val activity = LocalActivity.current
    val signedIn = state.user != null
    // The check plays only on the moment of signing in, not on every app open
    var wasSignedIn by remember { mutableStateOf(signedIn) }
    var celebrating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(signedIn) {
        if (signedIn && !wasSignedIn) celebrating = true
        wasSignedIn = signedIn
    }
    val celebrate = signedIn && (celebrating || !wasSignedIn)
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        account.markPermissionsAsked()
    }
    val stage = when {
        !signedIn || celebrate -> Stage.SignIn
        state.role == null -> Stage.Role
        // Once, after the role, and only while something is still missing
        !state.permissionsAsked && blePermissions(state.role == AppRole.Instructor).any { !context.granted(it) } -> Stage.Permissions
        else -> Stage.Main
    }
    AnimatedContent(targetState = stage, label = "stage") { target ->
        when (target) {
            Stage.SignIn -> SignInScreen(
                signingIn = state.signingIn,
                error = state.error,
                signedIn = signedIn,
                onSignIn = { activity?.let(account::signIn) },
                onCelebrated = { celebrating = false }
            )
            Stage.Role -> RoleScreen(name = state.displayName, onChoose = account::setRole)
            Stage.Permissions -> NearbyPermissionsScreen(
                onAllow = { permissions.launch(blePermissions(state.role == AppRole.Instructor).toTypedArray()) },
                onLater = account::markPermissionsAsked
            )
            Stage.Main -> AntiProxyApp(account, state, joinCode, onJoinHandled)
        }
    }
}

private enum class Stage { SignIn, Role, Permissions, Main }

/** Where the user is; depth decides which way the transition slides. */
private sealed interface Screen {
    val depth: Int
    data object Home : Screen { override val depth = 0 }
    data class Class(val id: String) : Screen { override val depth = 1 }
    data object Settings : Screen { override val depth = 1 }
    data object About : Screen { override val depth = 2 }
    data class Roster(val id: String) : Screen { override val depth = 2 }

    /** Where Back goes. About is only reachable from Settings; the roster goes back to its class. */
    fun parent(): Screen = when (this) {
        About -> Settings
        is Roster -> Class(id)
        else -> Home
    }
}

/** Bluetooth permissions for an attendance action. Notifications are optional (the session's status notice). */
private fun blePermissions(teacher: Boolean): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (teacher) add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    // Android won't report nearby Bluetooth devices without location, on any version
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (teacher && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.bluetoothOn() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Home, a class, or Settings, plus the dialogs that can appear over any of them. */
@Composable
private fun AntiProxyApp(
    account: AccountViewModel,
    state: AccountState,
    joinCode: String?,
    onJoinHandled: () -> Unit,
    vm: MainViewModel = viewModel(),
    classVm: ClassroomViewModel = viewModel(),
    updateVm: UpdateViewModel = viewModel()
) {
    val role = state.role ?: AppRole.Instructor
    val updateSettings by updateVm.settings.collectAsStateWithLifecycle()
    val newRelease by updateVm.available.collectAsStateWithLifecycle()
    val showUpdateSheet by updateVm.showUpdateSheet.collectAsStateWithLifecycle()
    val checkingForUpdates by updateVm.checking.collectAsStateWithLifecycle()
    val download by updateVm.download.collectAsStateWithLifecycle()
    val changelog by updateVm.changelog.collectAsStateWithLifecycle()
    val showChangelog by updateVm.showChangelog.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val teaching by classVm.teaching.collectAsStateWithLifecycle()
    val joined by classVm.joined.collectAsStateWithLifecycle()
    val invite by classVm.invite.collectAsStateWithLifecycle()
    val prompts by classVm.prompts.collectAsStateWithLifecycle()
    val present by classVm.present.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(classVm) { classVm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(updateVm) { updateVm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(joinCode, role) {
        if (joinCode == null) return@LaunchedEffect
        if (role == AppRole.Student) classVm.lookUp(joinCode)
        else showMessage("Switch to Student in Settings, then open the invite link again.")
        onJoinHandled()
    }
    // Felt as well as seen: a confirm or reject buzz when a check-in finishes
    LaunchedEffect(checkIn) {
        when (checkIn) {
            is StudentCheckInState.Success -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            is StudentCheckInState.Rejected, is StudentCheckInState.Error -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    var openClassId by rememberSaveable { mutableStateOf<String?>(null) }
    var rosterOf by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var attendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var unenrollingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Permissions are asked when an attendance action needs them, not at launch
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askedTeacher by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = afterPermission
        afterPermission = null
        val required = blePermissions(askedTeacher).filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.all(context::granted)) action?.invoke()
        else showMessage("Allow Nearby devices so AntiProxy can use Bluetooth. You can do it in Settings.")
    }
    var afterBluetooth by remember { mutableStateOf<(() -> Unit)?>(null) }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = afterBluetooth
        afterBluetooth = null
        if (context.bluetoothOn()) action?.invoke()
        else showMessage("Bluetooth is needed for attendance.")
    }
    fun withBluetooth(teacher: Boolean, action: () -> Unit) {
        val wanted = blePermissions(teacher)
        val required = wanted.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        when {
            !required.all(context::granted) -> {
                afterPermission = action
                askedTeacher = teacher
                permissionLauncher.launch(wanted.filterNot(context::granted).toTypedArray())
            }
            // Asks the system to switch Bluetooth on, the way Maps asks for location, then carries on with the action
            !context.bluetoothOn() -> {
                afterBluetooth = action
                bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> action()
        }
    }

    val running = session.isRunning
    val endAttendance: (Classroom) -> Unit = { c ->
        attendingId = null
        classVm.endAttendance(c)
        vm.stopSession()
    }
    // Without Bluetooth the broadcast never starts, so no session is opened that students would be prompted for
    val takeAttendance: (Classroom) -> Unit = { c ->
        withBluetooth(teacher = true) {
            attendingId = c.id
            classVm.startAttendance(c)
            vm.startSession()
        }
    }
    val markAttendance: () -> Unit = { withBluetooth(teacher = false) { vm.checkIn() } }

    // Each verified check-in goes to Firestore under the open session; offline it waits in Firestore's queue
    val lastRecord = session.recentRecords.firstOrNull()
    LaunchedEffect(lastRecord) {
        val record = lastRecord ?: return@LaunchedEffect
        attendingId?.let { classVm.recordAttendance(it, record) }
    }

    val liveClassIds: Set<String> =
        if (role == AppRole.Instructor) setOfNotNull(attendingId.takeIf { running })
        else prompts.map { it.classroom.id }.toSet()

    val screen: Screen = when {
        showAbout -> Screen.About
        showSettings -> Screen.Settings
        rosterOf != null && rosterOf == openClassId && teaching.any { it.classroom.id == rosterOf } -> Screen.Roster(rosterOf!!)
        openClassId != null && (
            if (role == AppRole.Instructor) teaching.any { it.classroom.id == openClassId }
            else joined.any { it.classroom.id == openClassId }
        ) -> Screen.Class(openClassId!!)
        else -> Screen.Home
    }
    val still = reduceMotion()

    // Seekable, so the back gesture can scrub the transition with the finger (predictive back)
    val screenState = remember { SeekableTransitionState<Screen>(screen) }
    LaunchedEffect(screen) { if (screenState.currentState != screen || screenState.targetState != screen) screenState.animateTo(screen) }
    val goBack: () -> Unit = {
        when {
            showAbout -> showAbout = false
            showSettings -> showSettings = false
            rosterOf != null -> rosterOf = null
            else -> openClassId = null
        }
    }
    // While the finger moves, the current screen slides right and shrinks and the previous one shows
    // underneath. Lifting commits; swiping back out cancels and springs it back.
    PredictiveBackHandler(enabled = screen != Screen.Home) { gesture ->
        val previous = screen.parent()
        try {
            gesture.collect { event -> if (!still) screenState.seekTo(event.progress, previous) }
            goBack()
        } catch (e: CancellationException) {
            // This coroutine is already cancelled, so spring back from the screen's own scope
            scope.launch { screenState.animateTo(screen) }
            throw e
        }
    }

    // Expressive navigation (AttendX's springs): deeper screens slide in from the right, back reverses it
    rememberTransition(screenState, label = "screen").AnimatedContent(
        transitionSpec = {
            when {
                still -> EnterTransition.None togetherWith ExitTransition.None
                targetState.depth >= initialState.depth -> slideFadeInFromRight() togetherWith slideFadeOutToLeft()
                else -> slideFadeInFromLeft() togetherWith slideFadeOutToRight()
            }
        }
    ) { s ->
        when (s) {
            Screen.About -> AboutScreen(
                onBack = { showAbout = false },
                onChangelog = updateVm::openChangelog,
                onCheckForUpdates = { updateVm.check(manual = true) }
            )

            is Screen.Roster -> {
                val roster = teaching.firstOrNull { it.classroom.id == s.id }
                if (roster != null) AttendanceRosterScreen(
                    classroom = roster.classroom,
                    members = roster.members,
                    presentIds = present[s.id].orEmpty(),
                    onBack = { rosterOf = null }
                )
            }

            Screen.Settings -> SettingsScreen(
                name = state.displayName.orEmpty(),
                email = state.user?.email.orEmpty(),
                photoUrl = state.user?.photoUrl?.toString().orEmpty(),
                role = role,
                themeMode = state.themeMode,
                snackbar = snackbar,
                onBack = { showSettings = false },
                onRename = { account.updateName(it, showMessage) },
                onSignOut = account::signOut,
                onRole = {
                    account.setRole(it)
                    openClassId = null
                },
                onTheme = account::setThemeMode,
                updates = updateSettings,
                checkingForUpdates = checkingForUpdates,
                onCheckForUpdates = { updateVm.check(manual = true) },
                onUpdateSettings = updateVm::update,
                onAbout = { showAbout = true },
                onChangelog = updateVm::openChangelog
            )

            is Screen.Class -> {
                // Looked up from the lists, not openClassId, so a class sliding out after Back still renders
                val openTeaching = teaching.firstOrNull { it.classroom.id == s.id }.takeIf { role == AppRole.Instructor }
                val openJoined = joined.firstOrNull { it.classroom.id == s.id }.takeIf { role == AppRole.Student }
                when {
                    openTeaching != null -> {
                        val c = openTeaching.classroom
                        val attending = attendingId == c.id && running
                        ClassScreen(
                            classroom = c,
                            members = openTeaching.members,
                            myUid = state.user?.uid,
                            snackbar = snackbar,
                            onBack = { openClassId = null },
                            teacher = TeacherControls(
                                attending = attending,
                                presentIds = if (attending) present[c.id].orEmpty() else emptySet(),
                                activeSession = session.activeSession.takeIf { attending },
                                onTakeAttendance = { takeAttendance(c) },
                                onEndAttendance = { endAttendance(c) },
                                onOpenRoster = { rosterOf = c.id },
                                onResetCode = { classVm.resetCode(c) },
                                onApprove = { classVm.approveDevice(c, it) },
                                onRemove = { classVm.removeMember(c, it) },
                                onEdit = { editingId = c.id },
                                onDelete = { deletingId = c.id }
                            ),
                            student = null
                        )
                    }

                    openJoined != null -> {
                        // Students read the class list from Firestore; teachers already have it with their classes
                        val classmates by remember(s.id) { classVm.people(s.id) }.collectAsStateWithLifecycle(emptyList())
                        val c = openJoined.classroom
                        val prompt = prompts.firstOrNull { it.classroom.id == c.id }
                        ClassScreen(
                            classroom = c,
                            members = classmates,
                            myUid = state.user?.uid,
                            snackbar = snackbar,
                            onBack = { openClassId = null },
                            teacher = null,
                            student = StudentControls(
                                status = openJoined.status,
                                checkIn = checkIn,
                                history = classVm.recordsFor(c, history),
                                prompt = prompt,
                                onMarkAttendance = {
                                    prompt?.let(classVm::dismissPrompt)
                                    markAttendance()
                                },
                                onUnenroll = { unenrollingId = c.id }
                            )
                        )
                    }
                }
            }

            Screen.Home -> HomeScreen(
                role = role,
                userName = state.displayName.orEmpty(),
                userPhoto = state.user?.photoUrl?.toString().orEmpty(),
                teaching = teaching,
                joined = joined,
                liveClassIds = liveClassIds,
                snackbar = snackbar,
                onOpenClass = { openClassId = it },
                onSettings = { showSettings = true },
                newVersion = newRelease?.tagName,
                onUpdate = updateVm::openUpdateSheet,
                onNewClass = { creating = true },
                onJoinClass = classVm::openJoin,
                onEdit = { editingId = it.id },
                onDelete = { deletingId = it.id },
                onUnenroll = { unenrollingId = it.id }
            )
        }
    }

    if (creating) {
        ClassFormDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, details ->
                creating = false
                classVm.createClassroom(name, details)
            }
        )
    }

    teaching.firstOrNull { it.classroom.id == editingId }?.let { tc ->
        ClassFormDialog(
            initial = tc.classroom,
            onDismiss = { editingId = null },
            onSave = { name, details ->
                editingId = null
                classVm.editClassroom(tc.classroom, name, details)
            }
        )
    }

    teaching.firstOrNull { it.classroom.id == deletingId }?.let { tc ->
        DeleteClassDialog(
            classroom = tc.classroom,
            students = tc.members.size,
            onDismiss = { deletingId = null },
            onDelete = {
                deletingId = null
                if (attendingId == tc.classroom.id) endAttendance(tc.classroom)
                classVm.deleteClassroom(tc.classroom)
            }
        )
    }

    joined.firstOrNull { it.classroom.id == unenrollingId }?.let { jc ->
        UnenrollDialog(
            classroom = jc.classroom,
            onDismiss = { unenrollingId = null },
            onUnenroll = {
                unenrollingId = null
                classVm.leave(jc.classroom)
            }
        )
    }

    InviteDialogs(
        state = invite,
        onLookUp = classVm::lookUp,
        onAccept = classVm::acceptInvite,
        onDismiss = classVm::closeInvite
    )

    prompts.firstOrNull()?.takeIf { role == AppRole.Student }?.let { prompt ->
        AttendancePromptDialog(
            prompt = prompt,
            onMark = {
                classVm.dismissPrompt(prompt)
                markAttendance()
            },
            onNotNow = { classVm.dismissPrompt(prompt) }
        )
    }

    // Shown in any role: a student can open the app as a teacher first
    val deletedNotice = joined.firstOrNull { it.status == MembershipStatus.ClassroomDeleted }
    deletedNotice?.let { deleted ->
        DeletedClassroomNotice(deleted.classroom, onOk = { classVm.acknowledgeDeletion(deleted.classroom) })
    }

    // Updates wait behind anything about attendance or classes, so prompts never stack
    val classPromptShowing = deletedNotice != null || (role == AppRole.Student && prompts.isNotEmpty())
    val release = newRelease
    if (showUpdateSheet && release != null && !classPromptShowing) {
        UpdateBottomSheet(
            release = release,
            settings = updateSettings,
            download = download,
            onDownload = { bestApk(release.assets)?.let(updateVm::download) },
            onCancelDownload = updateVm::cancelDownload,
            onDownloadHandled = updateVm::resetDownload,
            onNeverWarn = { updateVm.update { it.copy(warnBeforeDownload = false) } },
            onDismiss = updateVm::dismissUpdateSheet
        )
    } else if (showChangelog && !classPromptShowing) {
        ChangelogBottomSheet(
            releases = changelog.releases,
            loading = changelog.loading,
            failed = changelog.failed,
            onDismiss = updateVm::dismissChangelog
        )
    }
}
