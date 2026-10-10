@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package org.ap.protocol.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import antiproxy.core.protocol.Session
import org.ap.protocol.classroom.AttendancePrompt
import org.ap.protocol.classroom.ClassDetails
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.themeIndex
import org.ap.protocol.classroom.InviteState
import org.ap.protocol.classroom.JoinCode
import org.ap.protocol.classroom.JoinedClassroom
import org.ap.protocol.classroom.Member
import org.ap.protocol.classroom.MembershipStatus
import org.ap.protocol.classroom.TeacherClassroom

/** Share is the main action; copying and resetting the code live in the menu. */
@Composable
fun ShareSplitButton(onShare: () -> Unit, onCopy: () -> Unit, onReset: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share invite")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    modifier = Modifier.semantics { contentDescription = "More code options" }
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .rotate(if (menuOpen) 180f else 0f)
                    )
                }
            }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Copy code") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                onClick = { menuOpen = false; onCopy() }
            )
            DropdownMenuItem(
                text = { Text("Get a new code") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                onClick = { menuOpen = false; onReset() }
            )
        }
    }
}

fun shareInvite(context: Context, classroom: Classroom) {
    val text = "Join my class \"${classroom.name}\" on AntiProxy: ${JoinCode.link(classroom.joinCode)}\n" +
        "Or enter code ${JoinCode.display(classroom.joinCode)}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share invite"))
}

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Code entry, then "You were invited to X by Y". Join links (Phase 5) jump straight to the invite. */
@Composable
fun InviteDialogs(
    state: InviteState,
    onLookUp: (String) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        InviteState.Closed -> Unit

        InviteState.EnteringCode, InviteState.Looking, is InviteState.Problem -> {
            var code by rememberSaveable { mutableStateOf("") }
            val looking = state == InviteState.Looking
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Join a classroom") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(10) },
                            label = { Text("Code from your teacher") },
                            placeholder = { Text("K7M-4QX") },
                            singleLine = true,
                            enabled = !looking,
                            isError = state is InviteState.Problem,
                            supportingText = (state as? InviteState.Problem)?.let { problem -> { Text(problem.message) } },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { onLookUp(code) }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (looking) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ContainedLoadingIndicator(Modifier.size(40.dp))
                                Text("Checking the code…")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onLookUp(code) }, enabled = !looking && code.isNotBlank()) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
            )
        }

        is InviteState.Found -> AlertDialog(
            onDismissRequest = { if (!state.joining) onDismiss() },
            icon = { Icon(Icons.Rounded.GroupAdd, contentDescription = null) },
            title = { Text("Join ${state.classroom.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(boldName("You were invited to ${state.classroom.name} by ", state.classroom.teacherName, "."))
                    Text(
                        boldName("", state.classroom.teacherName, " will see your name and can take your attendance."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.joining) ContainedLoadingIndicator(Modifier.size(40.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = onAccept, enabled = !state.joining) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.joining) { Text("Not now") }
            }
        )
    }
}

/** Opened by a teacher's session; Mark attendance runs the normal BLE check-in. */
@Composable
fun AttendancePromptDialog(prompt: AttendancePrompt, onMark: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(Icons.Rounded.HowToReg, contentDescription = null) },
        title = { Text("${prompt.classroom.teacherName} is taking attendance") },
        text = { Text("${prompt.classroom.name}. Mark attendance while you're in the room.") },
        confirmButton = { TextButton(onClick = onMark) { Text("Mark attendance") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } }
    )
}

/** Shows a name in bold, so students can spot who invited them or who deleted the class. */
fun boldName(before: String, name: String, after: String) = buildAnnotatedString {
    append(before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) }
    append(after)
}

/** Once per deleted classroom: OK drops the student's list entry, so it never shows twice. */
@Composable
fun DeletedClassroomNotice(classroom: Classroom, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        title = { Text("You were removed from ${classroom.name}") },
        text = { Text(boldName("Reason: classroom was deleted by ", classroom.teacherName, ".")) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } }
    )
}

// ==================== Create, edit, delete, unenroll ====================

/** Create ([initial] null) or edit a class: name, section, room and header artwork. */
@Composable
fun ClassFormDialog(
    initial: Classroom?,
    onDismiss: () -> Unit,
    onSave: (name: String, details: ClassDetails) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var section by rememberSaveable { mutableStateOf(initial?.section.orEmpty()) }
    var room by rememberSaveable { mutableStateOf(initial?.room.orEmpty()) }
    var theme by rememberSaveable { mutableStateOf(initial?.themeIndex ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New class" else "Edit class") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(80) },
                    label = { Text("Class name (required)") }, placeholder = { Text("Physics 101") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = section, onValueChange = { section = it.take(80) },
                    label = { Text("Section") }, placeholder = { Text("B.Tech, 2nd year") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = room, onValueChange = { room = it.take(40) },
                    label = { Text("Room") }, placeholder = { Text("Lab 2") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Header artwork", style = MaterialTheme.typography.titleSmall)
                ThemePicker(selected = theme, onSelect = { theme = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, ClassDetails(section, room, theme)) },
                enabled = name.isNotBlank()
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DeleteClassDialog(classroom: Classroom, students: Int, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        title = { Text("Delete ${classroom.name}?") },
        text = {
            Text(
                "This removes ${plural(students, "student")} from the class. " +
                    "They'll see a notice the next time they open AntiProxy. This can't be undone."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun UnenrollDialog(classroom: Classroom, onDismiss: () -> Unit, onUnenroll: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null) },
        title = { Text("Unenroll from ${classroom.name}?") },
        text = {
            Text(boldName("You won't be able to mark attendance here. Attendance already recorded stays with ", classroom.teacherName, ". You'll need the code again to rejoin."))
        },
        confirmButton = { TextButton(onClick = onUnenroll) { Text("Unenroll") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

