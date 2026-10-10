@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package org.ap.protocol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import antiproxy.core.protocol.Session
import antiproxy.core.storage.AttendanceRecord
import org.ap.protocol.classroom.AttendancePrompt
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.JoinCode
import org.ap.protocol.classroom.Member
import org.ap.protocol.classroom.MembershipStatus
import org.ap.protocol.classroom.themeIndex
import org.ap.protocol.flow.StudentCheckInState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What only the class's teacher can see and do. */
class TeacherControls(
    val attending: Boolean,
    val presentCount: Int,
    val presentNames: List<String>,
    val activeSession: Session?,
    val onTakeAttendance: () -> Unit,
    val onEndAttendance: () -> Unit,
    val onResetCode: () -> Unit,
    val onApprove: (Member) -> Unit,
    val onRemove: (Member) -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit
)

/** What a student in the class can see and do. */
class StudentControls(
    val status: MembershipStatus,
    val checkIn: StudentCheckInState,
    val history: List<AttendanceRecord>,
    val prompt: AttendancePrompt?,
    val onMarkAttendance: () -> Unit,
    val onUnenroll: () -> Unit
)

private enum class ClassTab(val label: String) { Attendance("Attendance"), People("People") }

/** One class: artwork banner, then Attendance and People tabs. Exactly one of [teacher] / [student] is set. */
@Composable
fun ClassScreen(
    classroom: Classroom,
    members: List<Member>,
    myUid: String?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    teacher: TeacherControls?,
    student: StudentControls?
) {
    // System Back is handled app-wide by the predictive back handler in MainActivity
    var tab by rememberSaveable { mutableStateOf(ClassTab.Attendance) }
    var removing by remember { mutableStateOf<Member?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val live = teacher?.attending == true || student?.prompt != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(classroom.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ClassMenu(
                        teacher = teacher,
                        student = student,
                        onReset = { confirmReset = true }
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            animatedItem("banner") { ClassBanner(classroom, live, showTeacher = student != null) }
            animatedItem("tabs") {
                val tabs: @Composable () -> Unit = {
                    ClassTab.entries.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(t.label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                // Large text: tabs as wide as their labels (scrollable), so "Attendance" never wraps
                if (LocalDensity.current.fontScale >= 1.3f) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                        tabs = tabs
                    )
                } else {
                    PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, tabs = tabs)
                }
            }
            when (tab) {
                ClassTab.Attendance -> {
                    if (teacher != null) {
                        animatedItem("attendance") { TeacherAttendanceCard(classroom, teacher) }
                        animatedItem("code") { JoinCodeCard(classroom, onReset = { confirmReset = true }) }
                    }
                    if (student != null) {
                        if (student.status == MembershipStatus.AwaitingApproval) {
                            animatedItem("approval") {
                                SectionCard("New phone") {
                                    EmptyLine("Waiting for ${classroom.teacherName} to approve this phone. You can mark attendance once they do.")
                                }
                            }
                        }
                        animatedItem("mark") { StudentAttendanceCard(classroom, student) }
                        animatedItem("history") { HistoryCard(student.history) }
                    }
                }
                ClassTab.People -> {
                    val students = members.sortedBy { it.name.lowercase() }
                    animatedItem("teacher-title") { PeopleHeader("Teacher", count = null) }
                    animatedItem("teacher") {
                        PersonRow(
                            name = classroom.teacherName,
                            photoUrl = classroom.teacherPhotoUrl,
                            isYou = teacher != null
                        )
                    }
                    animatedItem("students-title") { PeopleHeader("Students", count = students.size) }
                    if (students.isEmpty()) {
                        animatedItem("no-students") {
                            EmptyLine(
                                if (teacher != null) "No one has joined yet. Share the code from the Attendance tab."
                                else "No other students yet."
                            )
                        }
                    }
                    items(students, key = { it.uid }) { m ->
                        val waiting = m.pendingDevicePublicKey.isNotEmpty()
                        AnimatedRow {
                        PersonRow(
                            name = m.name,
                            photoUrl = m.photoUrl,
                            isYou = m.uid == myUid,
                            detail = if (teacher != null && waiting) "Switched to a new phone. Approve it so they can mark attendance." else null,
                            actions = if (teacher == null) null else {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (waiting) FilledTonalButton(onClick = { teacher.onApprove(m) }) { Text("Approve") }
                                        IconButton(onClick = { removing = m }) {
                                            Icon(Icons.Rounded.PersonRemove, contentDescription = "Remove ${m.name}")
                                        }
                                    }
                                }
                            }
                        )
                        }
                    }
                }
            }
        }
    }

    if (confirmReset && teacher != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Get a new code?") },
            text = { Text("The current code and link stop working. Students who already joined stay in the class.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; teacher.onResetCode() }) { Text("New code") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }

    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.name}?") },
            text = { Text("They can no longer mark attendance here. They can rejoin with the code.") },
            confirmButton = { TextButton(onClick = { removing = null; teacher?.onRemove(member) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ClassMenu(teacher: TeacherControls?, student: StudentControls?, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Class options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (teacher != null) {
                DropdownMenuItem(
                    text = { Text("Edit class") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = { open = false; teacher.onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Get a new code") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    onClick = { open = false; onReset() }
                )
                DropdownMenuItem(
                    text = { Text("Delete class") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = { open = false; teacher.onDelete() }
                )
            }
            if (student != null) {
                DropdownMenuItem(
                    text = { Text("Unenroll") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null) },
                    onClick = { open = false; student.onUnenroll() }
                )
            }
        }
    }
}

@Composable
private fun ClassBanner(classroom: Classroom, live: Boolean, showTeacher: Boolean) {
    ClassArtwork(theme = classroom.themeIndex, modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp)) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, top = 20.dp, end = 72.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (live) LiveBadge()
            Text(
                classroom.name,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            val details = listOfNotNull(
                classroom.section.takeIf { it.isNotEmpty() },
                classroom.room.takeIf { it.isNotEmpty() }?.let { "Room $it" }
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            if (showTeacher) {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Avatar(classroom.teacherName, classroom.teacherPhotoUrl, size = 28.dp)
                    Text(classroom.teacherName, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TeacherAttendanceCard(classroom: Classroom, teacher: TeacherControls) {
    SectionCard("Attendance") {
        if (teacher.attending) {
            Text("Attendance running · ${teacher.presentCount} present", style = MaterialTheme.typography.titleMedium)
            teacher.activeSession?.let { TimeLeft(issuedAt = it.issuedAt, expiresAt = it.expiresAt) }
            if (teacher.presentNames.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    teacher.presentNames.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            ActionButton(
                text = "End attendance",
                icon = Icons.Rounded.Stop,
                onClick = teacher.onEndAttendance,
                kind = ButtonKind.Tonal,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            EmptyLine("Students who open AntiProxy see \"${classroom.teacherName} is taking attendance\" and can mark it in range for 5 minutes.")
            ActionButton(
                text = "Take attendance",
                icon = Icons.Rounded.PlayArrow,
                onClick = teacher.onTakeAttendance,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun JoinCodeCard(classroom: Classroom, onReset: () -> Unit) {
    val context = LocalContext.current
    SectionCard("Join code") {
        Text(
            JoinCode.display(classroom.joinCode),
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.semantics { contentDescription = "Join code ${JoinCode.spoken(classroom.joinCode)}" }
        )
        EmptyLine("Students enter this code, or tap the invite you share.")
        ShareSplitButton(
            onShare = { shareInvite(context, classroom) },
            onCopy = { copyText(context, "Join code", JoinCode.display(classroom.joinCode)) },
            onReset = onReset
        )
    }
}

@Composable
private fun StudentAttendanceCard(classroom: Classroom, student: StudentControls) {
    val approved = student.status == MembershipStatus.Active
    SectionCard("Mark attendance") {
        if (student.prompt != null) {
            Text(boldName("", classroom.teacherName, " is taking attendance now."), style = MaterialTheme.typography.bodyLarge)
            ActionButton(
                text = "Mark attendance",
                icon = Icons.Rounded.HowToReg,
                onClick = student.onMarkAttendance,
                enabled = approved && !student.checkIn.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            ActionButton(
                text = "Check in",
                icon = Icons.Rounded.HowToReg,
                onClick = student.onMarkAttendance,
                enabled = approved && !student.checkIn.isBusy,
                kind = ButtonKind.Tonal,
                modifier = Modifier.fillMaxWidth()
            )
        }
        CheckInStatus(student.checkIn)
    }
}

@Composable
private fun HistoryCard(history: List<AttendanceRecord>) {
    SectionCard("Your attendance") {
        if (history.isEmpty()) {
            EmptyLine("No attendance recorded in this class yet.")
        } else {
            val format = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.forEach { record ->
                    RecordRow(title = format.format(Date(record.recordedAt * 1000)), detail = "Marked present")
                }
            }
        }
    }
}

/** Section title like Classroom's "Teachers" / "Classmates"; TalkBack hears the count too. */
@Composable
private fun PeopleHeader(title: String, count: Int?) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    heading()
                    if (count != null) contentDescription = "$title, ${if (count == 1) "1 person" else "$count people"}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            if (count != null) {
                Icon(Icons.Rounded.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text("$count", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

@Composable
private fun PersonRow(
    name: String,
    photoUrl: String,
    isYou: Boolean,
    detail: String? = null,
    actions: (@Composable () -> Unit)? = null
) {
    ListItem(
        leadingContent = { Avatar(name, photoUrl, size = 40.dp) },
        headlineContent = { Text(if (isYou) "$name (you)" else name) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = actions,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** A list item that fades and slides into place (tab switches, people joining or leaving). */
private fun LazyListScope.animatedItem(key: String, content: @Composable () -> Unit) =
    item(key = key) { AnimatedRow(content) }

@Composable
private fun LazyItemScope.AnimatedRow(content: @Composable () -> Unit) {
    val still = reduceMotion()
    Box(
        if (still) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
        else Modifier.animateItem()
    ) { content() }
}
