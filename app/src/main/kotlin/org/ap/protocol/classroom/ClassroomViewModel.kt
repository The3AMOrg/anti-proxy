package org.ap.protocol.classroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.computeKeyId
import antiproxy.core.storage.AttendanceRecord
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ap.protocol.keystore.AndroidKeyManager
import org.ap.protocol.keystore.KeyAliases
import org.ap.protocol.storage.AndroidAuthorityStore
import org.ap.protocol.storage.AndroidStudentStore
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/** Same length as the BLE session the teacher's phone broadcasts (MainViewModel.startSession). */
private const val ATTENDANCE_MS = 5 * 60 * 1000L

/** A teacher's open attendance session that this student can mark in. */
data class AttendancePrompt(val classroom: Classroom, val sessionId: String, val endsAt: Long)

data class TeacherClassroom(val classroom: Classroom, val members: List<Member>)

enum class MembershipStatus { Active, AwaitingApproval, Removed, ClassroomDeleted }

data class JoinedClassroom(val classroom: Classroom, val status: MembershipStatus, val member: Member?)

sealed interface InviteState {
    data object Closed : InviteState
    data object EnteringCode : InviteState
    data object Looking : InviteState
    data class Found(val classroom: Classroom, val joining: Boolean = false) : InviteState
    data class Problem(val message: String) : InviteState
}

/**
 * Classrooms for both roles, and the key exchange that replaces copy/pasting keys:
 * - teacher's phone: caches every approved member key into the roster, revokes keys of
 *   removed students or replaced phones, and republishes its own key if it changed;
 * - student's phone: pins each classroom's teacher key, and requests approval when it is a new phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassroomViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ClassroomRepository()
    private val auth = FirebaseAuth.getInstance()
    private val keyManager = AndroidKeyManager(app)
    private val authorityStore = AndroidAuthorityStore(app)
    private val studentStore = AndroidStudentStore(app)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val messages: SharedFlow<String> = _messages

    private val _invite = MutableStateFlow<InviteState>(InviteState.Closed)
    val invite: StateFlow<InviteState> = _invite.asStateFlow()

    private val uid: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    val teaching: StateFlow<List<TeacherClassroom>> = uid
        .flatMapLatest { u -> if (u == null) flowOf(Live(emptyList(), confirmed = false)) else teacherClassrooms(u) }
        .onEach(::syncTeacherKeys)
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val joined: StateFlow<List<JoinedClassroom>> = uid
        .flatMapLatest { u -> if (u == null) flowOf(emptyList()) else joinedClassrooms(u) }
        .onEach(::syncStudentKeys)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Session docs this phone opened, by classroom id. */
    private val openSessions = mutableMapOf<String, String>()
    private val dismissedSessions = MutableStateFlow<Set<String>>(emptySet())

    /** Open sessions for classes where this student is approved. Ends once the session time is up. */
    val prompts: StateFlow<List<AttendancePrompt>> = joined
        .map { rows -> rows.filter { it.status == MembershipStatus.Active }.map { it.classroom } }
        .distinctUntilChanged()
        .flatMapLatest { classes ->
            if (classes.isEmpty()) flowOf(emptyList())
            else combine(classes.map { c ->
                repo.activeSessions(c.id).map { live -> live.value.map { AttendancePrompt(c, it.id, it.endsAt) } }
            }) { lists -> lists.toList().flatten() }
        }
        .combine(dismissedSessions) { all, gone ->
            all.filter { it.sessionId !in gone && it.endsAt > System.currentTimeMillis() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun teacherClassrooms(uid: String): Flow<Live<List<TeacherClassroom>>> =
        repo.teacherClassrooms(uid).flatMapLatest { classrooms ->
            if (classrooms.value.isEmpty()) flowOf(Live(emptyList(), classrooms.confirmed))
            else combine(classrooms.value.map { c -> repo.members(c.id).map { c to it } }) { rows ->
                Live(
                    rows.map { (c, members) -> TeacherClassroom(c, members.value) },
                    confirmed = classrooms.confirmed && rows.all { (_, members) -> members.confirmed }
                )
            }
        }

    private fun joinedClassrooms(uid: String): Flow<List<JoinedClassroom>> =
        repo.joinedClassroomIds(uid).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map { id ->
                combine(repo.classroom(id), repo.member(id, uid)) { classroom, member ->
                    classroom?.let { JoinedClassroom(it, statusOf(it, member), member) }
                }
            }) { rows -> rows.filterNotNull().sortedBy { it.classroom.name.lowercase() } }
        }

    private fun statusOf(classroom: Classroom, member: Member?): MembershipStatus = when {
        classroom.deleted -> MembershipStatus.ClassroomDeleted
        member == null -> MembershipStatus.Removed
        member.devicePublicKey == myStudentKey() -> MembershipStatus.Active
        else -> MembershipStatus.AwaitingApproval
    }

    // ---------- Key exchange ----------

    private fun syncTeacherKeys(live: Live<List<TeacherClassroom>>) {
        val classrooms = live.value
        val myKey = myAuthorityKey()
        if (myKey != null) {
            classrooms.filter { it.classroom.authorityPublicKey != myKey }.forEach {
                launchSafely { repo.updateAuthorityKey(it.classroom.id, myKey) }
            }
        }
        // Same idea for the avatar: follow the Google account's current photo
        val photo = myPhoto()
        if (photo.isNotEmpty()) {
            classrooms.filter { it.classroom.teacherPhotoUrl != photo }.forEach {
                launchSafely { repo.updateTeacherPhoto(it.classroom.id, photo) }
            }
        }

        // The roster is exactly the approved keys of current members across all classrooms.
        // Revoke only on server-confirmed data: offline, the cached roster must keep working.
        val approved = classrooms.flatMap { it.members }
            .mapNotNull { m -> decode(m.devicePublicKey)?.let { computeKeyId(it) to it } }
            .toMap()
        if (live.confirmed) {
            val approvedIds = approved.keys.map { it.bytes.toList() }.toSet()
            authorityStore.getAllStudentKeys()
                .filter { (kid, _) -> kid.bytes.toList() !in approvedIds }
                .forEach { (kid, _) -> authorityStore.revokeDevice(kid.bytes) }
        }
        approved.forEach { (kid, pub) -> authorityStore.cacheStudentKey(kid, pub) }
    }

    private fun syncStudentKeys(classrooms: List<JoinedClassroom>) {
        val uid = auth.currentUser?.uid ?: return
        val name = auth.currentUser?.displayName.orEmpty()
        val myKey = myStudentKey() ?: return
        classrooms.forEach { joined ->
            when (joined.status) {
                MembershipStatus.Active -> {
                    decode(joined.classroom.authorityPublicKey)?.let { studentStore.saveTrustedAuthorityKey(computeKeyId(it), it) }
                    val photo = myPhoto()
                    if (photo.isNotEmpty() && joined.member?.photoUrl != photo) {
                        launchSafely { repo.updateMemberPhoto(joined.classroom.id, uid, photo) }
                    }
                }
                MembershipStatus.AwaitingApproval ->
                    if (joined.member?.pendingDevicePublicKey != myKey) {
                        launchSafely { repo.requestDeviceChange(joined.classroom.id, uid, name, myKey) }
                    }
                MembershipStatus.Removed ->
                    launchSafely { repo.forget(joined.classroom.id, uid) }
                // Kept until the student dismisses the notice (acknowledgeDeletion)
                MembershipStatus.ClassroomDeleted -> Unit
            }
        }
    }

    // ---------- Teacher actions ----------

    fun createClassroom(name: String, details: ClassDetails) = launchSafely {
        val user = auth.currentUser ?: return@launchSafely
        if (!keyManager.hasKey(KeyAliases.AUTHORITY)) keyManager.generateKey(KeyAliases.AUTHORITY)
        val key = myAuthorityKey() ?: error("No instructor key")
        // A new class gets a random artwork unless the teacher picked one
        val picked = details.copy(theme = details.theme.takeIf { it in 0 until CLASS_THEMES } ?: Random.nextInt(CLASS_THEMES))
        val classroom = repo.create(name.trim(), user.uid, user.displayName.orEmpty(), key, picked.trimmed(), myPhoto())
        _messages.emit("Created ${classroom.name}. Share code ${JoinCode.display(classroom.joinCode)} with your students.")
    }

    fun editClassroom(classroom: Classroom, name: String, details: ClassDetails) = launchSafely {
        val theme = details.theme.takeIf { it in 0 until CLASS_THEMES } ?: classroom.themeIndex
        repo.updateDetails(classroom.id, details.copy(theme = theme).trimmed(), name.trim())
        _messages.emit("Saved ${name.trim()}.")
    }

    /** Everyone in a class, for the People screen. Students can read this list too. */
    fun people(classroomId: String): Flow<List<Member>> = repo.members(classroomId).map { it.value }

    fun resetCode(classroom: Classroom) = launchSafely {
        repo.resetCode(classroom)
        _messages.emit("New code ready. The old one no longer works.")
    }

    fun approveDevice(classroom: Classroom, member: Member) = launchSafely {
        repo.approveDevice(classroom.id, member)
        _messages.emit("${member.name}'s new phone is approved.")
    }

    fun removeMember(classroom: Classroom, member: Member) = launchSafely {
        repo.removeMember(classroom.id, member.uid)
        _messages.emit("Removed ${member.name} from ${classroom.name}.")
    }

    /** Opens the session so members get the prompt. The caller starts the BLE broadcast. */
    fun startAttendance(classroom: Classroom) = launchSafely {
        val sid = UUID.randomUUID().toString()
        repo.openSession(classroom.id, sid, System.currentTimeMillis() + ATTENDANCE_MS)
        openSessions[classroom.id] = sid
    }

    /** Uploads a verified check-in under the open session. Matches the device key to a roster member. */
    fun recordAttendance(classroomId: String, record: AttendanceRecord) {
        val sid = openSessions[classroomId] ?: return
        val member = memberFor(classroomId, record) ?: return
        launchSafely { repo.writeRecord(classroomId, sid, member.uid, record) }
    }

    /** Names of the students verified so far, newest first, for the teacher's live list. */
    fun presentNames(classroomId: String, records: List<AttendanceRecord>): List<String> =
        records.mapNotNull { memberFor(classroomId, it)?.name }.distinct()

    /** A student's own records that came from this class's teacher phone. */
    fun recordsFor(classroom: Classroom, records: List<AttendanceRecord>): List<AttendanceRecord> {
        val teacherKey = decode(classroom.authorityPublicKey)?.let { computeKeyId(it).bytes } ?: return emptyList()
        return records.filter { it.authorityKeyId.contentEquals(teacherKey) }
    }

    private fun memberFor(classroomId: String, record: AttendanceRecord): Member? =
        teaching.value.firstOrNull { it.classroom.id == classroomId }?.members
            ?.firstOrNull { m -> decode(m.devicePublicKey)?.let { computeKeyId(it).bytes.contentEquals(record.deviceKeyId) } == true }

    fun endAttendance(classroom: Classroom) = launchSafely {
        openSessions.remove(classroom.id)?.let { repo.closeSession(classroom.id, it) }
    }

    fun deleteClassroom(classroom: Classroom) = launchSafely {
        repo.delete(classroom)
        _messages.emit("Deleted ${classroom.name}. Its students will see a notice the next time they open AntiProxy.")
    }

    // ---------- Student actions ----------

    fun openJoin() { _invite.value = InviteState.EnteringCode }

    fun closeInvite() { _invite.value = InviteState.Closed }

    /** Also the entry point for join links (Phase 5). */
    fun lookUp(rawCode: String) {
        val code = JoinCode.normalize(rawCode)
        if (!JoinCode.isValid(code)) {
            _invite.value = InviteState.Problem("Codes are 6 letters and numbers, like K7M-4QX.")
            return
        }
        _invite.value = InviteState.Looking
        viewModelScope.launch {
            _invite.value = try {
                repo.findByCode(code)?.let { InviteState.Found(it) }
                    ?: InviteState.Problem("No classroom uses that code. Check it with your teacher.")
            } catch (e: Exception) {
                InviteState.Problem("Couldn't check the code. Make sure you're online and try again.")
            }
        }
    }

    fun acceptInvite() {
        val found = _invite.value as? InviteState.Found ?: return
        val user = auth.currentUser ?: return
        val key = myStudentKey() ?: return
        _invite.value = found.copy(joining = true)
        viewModelScope.launch {
            try {
                repo.join(found.classroom, user.uid, user.displayName.orEmpty(), key, myPhoto())
                _invite.value = InviteState.Closed
                _messages.emit("You joined ${found.classroom.name}.")
            } catch (e: Exception) {
                _invite.value = InviteState.Problem("Couldn't join. Make sure you're online and try again.")
            }
        }
    }

    fun leave(classroom: Classroom) = launchSafely {
        val uid = auth.currentUser?.uid ?: return@launchSafely
        repo.leave(classroom.id, uid)
        _messages.emit("You left ${classroom.name}.")
    }

    fun dismissPrompt(prompt: AttendancePrompt) {
        dismissedSessions.value = dismissedSessions.value + prompt.sessionId
    }

    /** The student saw the "classroom was deleted" notice; dropping their list entry ends it. */
    fun acknowledgeDeletion(classroom: Classroom) = launchSafely {
        val uid = auth.currentUser?.uid ?: return@launchSafely
        repo.forget(classroom.id, uid)
    }

    // ---------- Helpers ----------

    private fun myPhoto(): String = auth.currentUser?.photoUrl?.toString().orEmpty()

    private fun ClassDetails.trimmed() = copy(section = section.trim().take(80), room = room.trim().take(40))

    private fun myAuthorityKey(): String? = keyManager.getPublicKey(KeyAliases.AUTHORITY)?.let { encode(it) }

    private fun myStudentKey(): String? = keyManager.getPublicKey(KeyAliases.STUDENT)?.let { encode(it) }

    private fun encode(key: PublicKeyBytes): String = Base64.getEncoder().encodeToString(key.bytes)

    private fun decode(base64: String): PublicKeyBytes? =
        runCatching { PublicKeyBytes(Base64.getDecoder().decode(base64)) }.getOrNull()

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Something went wrong. Try again.")
            }
        }
    }
}
