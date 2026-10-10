package org.ap.protocol.classroom

import com.google.firebase.Timestamp
import antiproxy.core.storage.AttendanceRecord
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.tasks.await
import java.util.Date

data class Classroom(
    val id: String,
    val name: String,
    val teacherUid: String,
    val teacherName: String,
    val authorityPublicKey: String,
    val joinCode: String,
    val deleted: Boolean = false,
    val section: String = "",
    val room: String = "",
    /** Built-in header artwork; -1 means "pick one from the id". */
    val theme: Int = -1,
    val teacherPhotoUrl: String = ""
)

/** A snapshot plus whether the server has confirmed it (false = local cache only). */
data class Live<T>(val value: T, val confirmed: Boolean)

data class AttendanceSession(val id: String, val endsAt: Long)

/** Number of built-in header artworks (ui/ClassArtwork.kt). Rules allow up to 16. */
const val CLASS_THEMES = 8

/** The artwork a classroom shows: its chosen theme, or one picked from its id. */
val Classroom.themeIndex: Int get() = if (theme in 0 until CLASS_THEMES) theme else Math.floorMod(id.hashCode(), CLASS_THEMES)

/** What the teacher fills in on the create / edit form, besides the name. */
data class ClassDetails(val section: String = "", val room: String = "", val theme: Int = -1)

data class Member(
    val uid: String,
    val name: String,
    val devicePublicKey: String,
    val pendingDevicePublicKey: String,
    val photoUrl: String = ""
)

/** Firestore access for classrooms. Paths and fields must match firestore.rules. */
class ClassroomRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private fun classroomRef(cid: String) = db.collection("classrooms").document(cid)
    private fun memberRef(cid: String, uid: String) = classroomRef(cid).collection("members").document(uid)
    private fun mirrorRef(uid: String, cid: String) =
        db.collection("users").document(uid).collection("classrooms").document(cid)

    // ---------- Live data ----------

    fun teacherClassrooms(uid: String): Flow<Live<List<Classroom>>> =
        db.collection("classrooms").whereEqualTo("teacherUid", uid)
            .observe { snap -> snap.documents.mapNotNull { it.toClassroom() }.filterNot { it.deleted }.sortedBy { it.name.lowercase() } }

    fun members(cid: String): Flow<Live<List<Member>>> =
        classroomRef(cid).collection("members")
            .observe { snap -> snap.documents.mapNotNull { it.toMember() }.sortedBy { it.name.lowercase() } }

    fun joinedClassroomIds(uid: String): Flow<List<String>> =
        db.collection("users").document(uid).collection("classrooms").observe { snap -> snap.documents.map { it.id } }
            .map { it.value }

    /** Sessions open now. Students also check endsAt, because a teacher's phone that dies never closes one. */
    fun activeSessions(cid: String): Flow<Live<List<AttendanceSession>>> =
        classroomRef(cid).collection("sessions").whereEqualTo("status", "ACTIVE")
            .observe { snap -> snap.documents.map { AttendanceSession(it.id, it.getTimestamp("endsAt")?.toDate()?.time ?: 0L) } }

    fun classroom(cid: String): Flow<Classroom?> = classroomRef(cid).observe { it.toClassroom() }.map { it.value }

    /** "No member" is only reported once the server confirms it; a cold offline cache must not look like removal. */
    fun member(cid: String, uid: String): Flow<Member?> = memberRef(cid, uid).observe { it.toMember() }
        .filter { it.value != null || it.confirmed }
        .map { it.value }

    // ---------- Teacher ----------

    /** Creates the classroom and claims its join code atomically. Retries if the code is taken. */
    suspend fun create(
        name: String,
        teacherUid: String,
        teacherName: String,
        authorityPublicKey: String,
        details: ClassDetails,
        teacherPhotoUrl: String
    ): Classroom {
        val ref = db.collection("classrooms").document()
        repeat(5) {
            val code = JoinCode.random()
            if (db.collection("joinCodes").document(code).get().await().exists()) return@repeat
            db.batch()
                .set(ref, mapOf(
                    "name" to name,
                    "teacherUid" to teacherUid,
                    "teacherName" to teacherName,
                    "authorityPublicKey" to authorityPublicKey,
                    "joinCode" to code,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "section" to details.section,
                    "room" to details.room,
                    "theme" to details.theme,
                    "teacherPhotoUrl" to teacherPhotoUrl
                ))
                .set(db.collection("joinCodes").document(code), mapOf("classroomId" to ref.id, "teacherUid" to teacherUid))
                .commit().await()
            return Classroom(ref.id, name, teacherUid, teacherName, authorityPublicKey, code,
                section = details.section, room = details.room, theme = details.theme, teacherPhotoUrl = teacherPhotoUrl)
        }
        error("Couldn't find a free join code. Try again.")
    }

    /**
     * Tombstones the classroom first, so no one can join it, then removes the members and the
     * join code. The classroom doc stays so students can read why they lost access.
     */
    suspend fun delete(classroom: Classroom) {
        db.batch()
            .update(classroomRef(classroom.id), "deleted", true)
            .delete(db.collection("joinCodes").document(classroom.joinCode))
            .commit().await()
        // ponytail: one batch, so it fails past 500 members; chunk it if a class ever gets that big
        val members = classroomRef(classroom.id).collection("members").get().await()
        val batch = db.batch()
        members.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    /** New code; the old one stops working immediately. */
    suspend fun resetCode(classroom: Classroom) {
        repeat(5) {
            val code = JoinCode.random()
            if (db.collection("joinCodes").document(code).get().await().exists()) return@repeat
            db.batch()
                .set(db.collection("joinCodes").document(code), mapOf("classroomId" to classroom.id, "teacherUid" to classroom.teacherUid))
                .update(classroomRef(classroom.id), "joinCode", code)
                .delete(db.collection("joinCodes").document(classroom.joinCode))
                .commit().await()
            return
        }
        error("Couldn't find a free join code. Try again.")
    }

    suspend fun updateDetails(cid: String, details: ClassDetails, name: String) {
        classroomRef(cid).update(mapOf(
            "name" to name,
            "section" to details.section,
            "room" to details.room,
            "theme" to details.theme
        )).await()
    }

    /** Photos follow the Google account; rules only accept the signed-in account's own photo. */
    suspend fun updateTeacherPhoto(cid: String, url: String) {
        classroomRef(cid).update("teacherPhotoUrl", url).await()
    }

    suspend fun updateMemberPhoto(cid: String, uid: String, url: String) {
        memberRef(cid, uid).update("photoUrl", url).await()
    }

    /** The teacher's phone changed (reinstall, new device), so students must pin the new key. */
    suspend fun updateAuthorityKey(cid: String, key: String) {
        classroomRef(cid).update("authorityPublicKey", key).await()
    }

    suspend fun approveDevice(cid: String, member: Member) {
        memberRef(cid, member.uid).update(
            mapOf("devicePublicKey" to member.pendingDevicePublicKey, "pendingDevicePublicKey" to "")
        ).await()
    }

    suspend fun openSession(cid: String, sid: String, endsAt: Long) {
        classroomRef(cid).collection("sessions").document(sid).set(mapOf(
            "status" to "ACTIVE",
            "startedAt" to FieldValue.serverTimestamp(),
            "endsAt" to Timestamp(Date(endsAt))
        )).await()
    }

    suspend fun closeSession(cid: String, sid: String) {
        classroomRef(cid).collection("sessions").document(sid).update("status", "ENDED").await()
    }

    /** Written by the teacher's phone after it verified a proof. Firestore queues it while offline. */
    suspend fun writeRecord(cid: String, sid: String, uid: String, record: AttendanceRecord) {
        classroomRef(cid).collection("sessions").document(sid).collection("records").document(uid).set(mapOf(
            "recordedAt" to Timestamp(Date(record.recordedAt * 1000)),
            "rssiMean" to record.rssiMean,
            "sampleCount" to record.sampleCount
        )).await()
    }

    suspend fun removeMember(cid: String, uid: String) {
        memberRef(cid, uid).delete().await()
    }

    // ---------- Student ----------

    /** Looks up a code for the invite dialog. Null if no classroom uses it. */
    suspend fun findByCode(code: String): Classroom? {
        val cid = db.collection("joinCodes").document(code).get().await().getString("classroomId") ?: return null
        return classroomRef(cid).get().await().toClassroom()
    }

    /**
     * Joins, or re-joins from a new phone. A new phone can't replace the approved key;
     * it goes in as pending until the teacher approves.
     */
    suspend fun join(classroom: Classroom, uid: String, name: String, deviceKey: String, photoUrl: String) {
        val existing = memberRef(classroom.id, uid).get().await().toMember()
        val batch = db.batch()
        when {
            existing == null -> batch.set(memberRef(classroom.id, uid), mapOf(
                "name" to name,
                "devicePublicKey" to deviceKey,
                "joinCode" to classroom.joinCode,
                "joinedAt" to FieldValue.serverTimestamp(),
                "photoUrl" to photoUrl
            ))
            existing.devicePublicKey != deviceKey -> batch.update(memberRef(classroom.id, uid),
                mapOf("name" to name, "pendingDevicePublicKey" to deviceKey))
        }
        // The list entry is create-only in the rules, so skip it on a re-join
        if (!mirrorRef(uid, classroom.id).get().await().exists()) {
            batch.set(mirrorRef(uid, classroom.id), mapOf("joinedAt" to FieldValue.serverTimestamp()))
        }
        batch.commit().await()
    }

    suspend fun requestDeviceChange(cid: String, uid: String, name: String, deviceKey: String) {
        memberRef(cid, uid).update(mapOf("name" to name, "pendingDevicePublicKey" to deviceKey)).await()
    }

    suspend fun leave(cid: String, uid: String) {
        db.batch().delete(memberRef(cid, uid)).delete(mirrorRef(uid, cid)).commit().await()
    }

    /** Removes the student's own list entry after the teacher removed them. */
    suspend fun forget(cid: String, uid: String) {
        mirrorRef(uid, cid).delete().await()
    }

    // ---------- Account ----------

    /** Firestore rules require stored names to match the Google account, so push renames everywhere. */
    suspend fun propagateName(uid: String, name: String) {
        val batch = db.batch()
        db.collection("classrooms").whereEqualTo("teacherUid", uid).get().await()
            .documents.forEach { batch.update(it.reference, "teacherName", name) }
        db.collection("users").document(uid).collection("classrooms").get().await()
            .documents.forEach { mirror ->
                val member = memberRef(mirror.id, uid)
                if (member.get().await().exists()) batch.update(member, "name", name)
            }
        batch.commit().await()
    }
}

// Listener rules:
// - Errors are never sent as empty data: an empty member list would look like "everyone
//   was removed" and revoke the whole roster.
// - Firestore ends a listener for good on any error, e.g. when a classroom's members are
//   watched a moment before the server has the new classroom. So re-listen with backoff;
//   the retry never gives up, so an error can't escape and crash the collector.
// - Metadata changes are included so the "now confirmed by the server" event arrives even
//   when the data itself is unchanged.
private fun <T> Query.observe(map: (QuerySnapshot) -> T): Flow<Live<T>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
        if (snap != null) trySend(Live(map(snap), !snap.metadata.isFromCache))
        else if (error != null) close(error)
    }
    awaitClose { registration.remove() }
}.retryWithBackoff().distinctUntilChanged()

private fun <T> DocumentReference.observe(map: (DocumentSnapshot) -> T): Flow<Live<T>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
        if (snap != null) trySend(Live(map(snap), !snap.metadata.isFromCache))
        else if (error != null) close(error)
    }
    awaitClose { registration.remove() }
}.retryWithBackoff().distinctUntilChanged()

// ponytail: fixed 1s→32s backoff, forever; add jitter if many listeners ever retry together
private fun <T> Flow<T>.retryWithBackoff(): Flow<T> = retryWhen { _, attempt ->
    delay(1000L shl attempt.toInt().coerceAtMost(5))
    true
}

private fun DocumentSnapshot.toClassroom(): Classroom? = if (!exists()) null else Classroom(
    id = id,
    name = getString("name").orEmpty(),
    teacherUid = getString("teacherUid").orEmpty(),
    teacherName = getString("teacherName").orEmpty(),
    authorityPublicKey = getString("authorityPublicKey").orEmpty(),
    joinCode = getString("joinCode").orEmpty(),
    deleted = getBoolean("deleted") == true,
    section = getString("section").orEmpty(),
    room = getString("room").orEmpty(),
    theme = getLong("theme")?.toInt() ?: -1,
    teacherPhotoUrl = getString("teacherPhotoUrl").orEmpty()
)

private fun DocumentSnapshot.toMember(): Member? = if (!exists()) null else Member(
    uid = id,
    name = getString("name").orEmpty(),
    devicePublicKey = getString("devicePublicKey").orEmpty(),
    pendingDevicePublicKey = getString("pendingDevicePublicKey").orEmpty(),
    photoUrl = getString("photoUrl").orEmpty()
)
