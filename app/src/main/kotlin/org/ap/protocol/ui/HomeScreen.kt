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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ap.protocol.account.AppRole
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.JoinCode
import org.ap.protocol.classroom.JoinedClassroom
import org.ap.protocol.classroom.MembershipStatus
import org.ap.protocol.classroom.TeacherClassroom
import org.ap.protocol.classroom.themeIndex

/** Every class for the current role, as Classroom-style cards with header artwork. */
@Composable
fun HomeScreen(
    role: AppRole,
    userName: String,
    userPhoto: String,
    teaching: List<TeacherClassroom>,
    joined: List<JoinedClassroom>,
    liveClassIds: Set<String>,
    snackbar: SnackbarHostState,
    onOpenClass: (String) -> Unit,
    onSettings: () -> Unit,
    newVersion: String?,
    onUpdate: () -> Unit,
    onNewClass: () -> Unit,
    onJoinClass: () -> Unit,
    onEdit: (Classroom) -> Unit,
    onDelete: (Classroom) -> Unit,
    onUnenroll: (Classroom) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val teacher = role == AppRole.Instructor
    // Removed / deleted classes are on their way out (a notice or a silent cleanup)
    val enrolled = joined.filter { it.status == MembershipStatus.Active || it.status == MembershipStatus.AwaitingApproval }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("AntiProxy") },
                subtitle = { Text(if (teacher) "Classes you teach" else "Your classes") },
                actions = {
                    // Stays until the update is installed, like AttendX's home-screen update button
                    if (newVersion != null) {
                        IconButton(onClick = onUpdate) {
                            Icon(
                                Icons.Rounded.SystemUpdate,
                                contentDescription = "Update to $newVersion",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics { contentDescription = "Settings and account" }
                    ) {
                        Avatar(userName, userPhoto, size = 32.dp)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            val fabLabel = if (teacher) "New class" else "Join class"
            ExtendedFloatingActionButton(
                onClick = if (teacher) onNewClass else onJoinClass,
                icon = { Icon(if (teacher) Icons.Rounded.Add else Icons.Rounded.GroupAdd, contentDescription = null) },
                text = { Text(fabLabel) },
                // This Material alpha leaves the extended FAB's text out of the accessibility tree
                modifier = Modifier.semantics { contentDescription = fabLabel }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val empty = if (teacher) teaching.isEmpty() else enrolled.isEmpty()
        if (empty) {
            EmptyHome(
                text = if (teacher) "Create a class, then share its code or link so students can join."
                else "Join your teacher's class with the code or link they shared.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp // room for the FAB
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (teacher) {
                items(teaching, key = { it.classroom.id }) { tc ->
                    val waiting = tc.members.count { it.pendingDevicePublicKey.isNotEmpty() }
                    ClassCard(
                        modifier = itemMotion(),
                        classroom = tc.classroom,
                        live = tc.classroom.id in liveClassIds,
                        onClick = { onOpenClass(tc.classroom.id) },
                        footer = {
                            Text(
                                "${plural(tc.members.size, "student")} · Code ${JoinCode.display(tc.classroom.joinCode)}" +
                                    if (waiting > 0) " · $waiting waiting" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        },
                        menu = { close ->
                            TeacherCardMenu(tc.classroom, close, onEdit = { onEdit(tc.classroom) }, onDelete = { onDelete(tc.classroom) })
                        }
                    )
                }
            } else {
                items(enrolled, key = { it.classroom.id }) { jc ->
                    val c = jc.classroom
                    ClassCard(
                        modifier = itemMotion(),
                        classroom = c,
                        live = c.id in liveClassIds,
                        onClick = { onOpenClass(c.id) },
                        footer = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (jc.status == MembershipStatus.AwaitingApproval) {
                                    Icon(Icons.Rounded.HourglassTop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Text("New phone · waiting for approval", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                } else {
                                    Avatar(c.teacherName, c.teacherPhotoUrl, size = 24.dp)
                                    Text(c.teacherName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                }
                            }
                        },
                        menu = { close ->
                            DropdownMenuItem(
                                text = { Text("Unenroll") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null) },
                                onClick = { close(); onUnenroll(c) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ClassArtwork(theme = 2, modifier = Modifier.size(width = 200.dp, height = 112.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Artwork header with the name, section and a footer line. Tapping opens the class; ⋮ opens [menu].
 * TalkBack reads the card as one item: name, section, footer, then "Taking attendance" if live.
 */
@Composable
private fun ClassCard(
    modifier: Modifier,
    classroom: Classroom,
    live: Boolean,
    onClick: () -> Unit,
    footer: @Composable () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth()
    ) {
        ClassArtwork(theme = classroom.themeIndex, modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp)) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    // Clear of the ⋮ button and the subject icon on the right
                    .padding(start = 20.dp, top = 16.dp, end = 88.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    classroom.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                if (classroom.section.isNotEmpty()) {
                    Text(classroom.section, style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
                if (live) LiveBadge()
                Box(Modifier.padding(top = 12.dp)) { footer() }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${classroom.name}", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

/** Never colour alone: an icon and the words "Taking attendance". */
@Composable
fun LiveBadge() {
    Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Color(0xFF0B57D0), modifier = Modifier.size(16.dp))
            Text("Taking attendance", style = MaterialTheme.typography.labelLarge, color = Color(0xFF0B57D0))
        }
    }
}

@Composable
private fun TeacherCardMenu(classroom: Classroom, close: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text("Share invite") },
        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
        onClick = { close(); shareInvite(context, classroom) }
    )
    DropdownMenuItem(
        text = { Text("Copy invite link") },
        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
        onClick = { close(); copyText(context, "Invite link", JoinCode.link(classroom.joinCode)) }
    )
    DropdownMenuItem(
        text = { Text("Edit class") },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        onClick = { close(); onEdit() }
    )
    DropdownMenuItem(
        text = { Text("Delete class") },
        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        onClick = { close(); onDelete() }
    )
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** New, removed and reordered class cards animate into place (off with "Remove animations"). */
@Composable
private fun LazyItemScope.itemMotion(): Modifier =
    if (reduceMotion()) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    else Modifier.animateItem()
