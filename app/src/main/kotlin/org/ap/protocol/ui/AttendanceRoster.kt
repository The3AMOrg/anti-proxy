@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package org.ap.protocol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.Member

private enum class RosterFilter(val label: String) { All("All"), Present("Present"), Absent("Absent") }

/** Everyone in the class, with the ones who have checked in highlighted. Opened from the live attendance card. */
@Composable
fun AttendanceRosterScreen(
    classroom: Classroom,
    members: List<Member>,
    presentIds: Set<String>,
    onBack: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(RosterFilter.All) }
    val presentCount = members.count { it.uid in presentIds }
    val counts = mapOf(
        RosterFilter.All to members.size,
        RosterFilter.Present to presentCount,
        RosterFilter.Absent to members.size - presentCount
    )
    // Present first, then by name, so the list reads the same way every time it updates
    val sorted = remember(members, presentIds) {
        members.sortedWith(compareBy({ it.uid !in presentIds }, { it.name.lowercase() }))
    }
    val shown = sorted.filter {
        when (filter) {
            RosterFilter.All -> true
            RosterFilter.Present -> it.uid in presentIds
            RosterFilter.Absent -> it.uid !in presentIds
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(classroom.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item("summary") {
                Text(
                    "$presentCount of ${members.size} present",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            item("filters") {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RosterFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text("${f.label} ${counts[f]}") },
                            leadingIcon = if (filter == f) {
                                { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item("empty") {
                    Text(
                        if (members.isEmpty()) "No one has joined this class yet." else "No one in this list.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(shown, key = { it.uid }) { member ->
                val isPresent = member.uid in presentIds
                ListItem(
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                    leadingContent = { Avatar(member.name, member.photoUrl, size = 40.dp) },
                    headlineContent = { Text(member.name) },
                    supportingContent = { Text(if (isPresent) "Present" else "Absent") },
                    trailingContent = {
                        if (isPresent) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isPresent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        headlineColor = if (isPresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        supportingColor = if (isPresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
