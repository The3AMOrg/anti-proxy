@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.ap.protocol.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.ap.protocol.R
import org.ap.protocol.update.DownloadState
import org.ap.protocol.update.Links
import org.ap.protocol.update.Release
import org.ap.protocol.update.UpdateSettings
import org.ap.protocol.update.bestApk
import org.ap.protocol.update.installApk
import org.ap.protocol.update.openUrl
import org.ap.protocol.update.versionLabel

/**
 * "Update available": what's new, then Download. With direct download on, the APK for this phone
 * downloads in the app and opens the installer; otherwise (or with no APK attached) the release
 * page opens in the browser.
 */
@Composable
fun UpdateBottomSheet(
    release: Release,
    settings: UpdateSettings,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDownloadHandled: () -> Unit,
    onNeverWarn: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val apk = bestApk(release.assets)
    var warning by rememberSaveable { mutableStateOf(false) }
    var pendingInstall by rememberSaveable { mutableStateOf<String?>(null) }
    var installMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // Back from "Install unknown apps": install if it's now allowed
    val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val path = pendingInstall ?: return@rememberLauncherForActivityResult
        pendingInstall = null
        if (context.packageManager.canRequestPackageInstalls()) context.installApk(java.io.File(path))
        else installMessage = "AntiProxy isn't allowed to install updates. Allow it in Settings, or download from GitHub."
    }

    LaunchedEffect(download) {
        if (download is DownloadState.Done) {
            onDownloadHandled()
            if (context.packageManager.canRequestPackageInstalls()) {
                context.installApk(download.file)
            } else {
                pendingInstall = download.file.path
                unknownSources.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                )
            }
        }
    }

    val start: () -> Unit = {
        installMessage = null
        if (settings.directDownload && apk != null) onDownload() else context.openUrl(release.htmlUrl.ifEmpty { Links.RELEASES })
    }
    val busy = download is DownloadState.Started || download is DownloadState.Progress

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Update available",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VersionChip("New ${release.tagName}", highlight = true)
                VersionChip("You have ${versionLabel()}", highlight = false)
            }
            if (release.notes.isNotBlank()) {
                Text("What's new", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) { MarkdownText(release.notes) }
            }

            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
                when (download) {
                    DownloadState.Started -> LoadingIndicator(Modifier.align(Alignment.Center))
                    is DownloadState.Progress -> Column(
                        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearWavyProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(download.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    is DownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
                    else -> EmptyLine(
                        installMessage ?: if (settings.directDownload && apk != null)
                            "Downloads ${apk.name} for this phone and opens the installer. Your classes and keys stay as they are."
                        else "Opens the release page on GitHub, where you can download the APK."
                    )
                }
            }

            // Standard-size buttons: two medium Expressive buttons at half width wrap "Download"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { if (busy) onCancelDownload() else onDismiss() },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) {
                    Icon(if (busy) Icons.Rounded.Close else Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Cancel" else "Later", maxLines = 1)
                }
                if (!busy) {
                    Button(
                        onClick = { if (settings.warnBeforeDownload) warning = true else start() },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download", maxLines = 1)
                    }
                }
            }
        }
    }

    if (warning) {
        DownloadWarningDialog(
            onDismiss = { warning = false },
            onConfirm = { neverAgain ->
                warning = false
                if (neverAgain) onNeverWarn()
                start()
            }
        )
    }
}

@Composable
private fun VersionChip(text: String, highlight: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** Updates come straight from GitHub, not a store: say so once, with "don't show again". */
@Composable
private fun DownloadWarningDialog(onDismiss: () -> Unit, onConfirm: (neverAgain: Boolean) -> Unit) {
    var neverAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("Download from GitHub?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This update comes straight from the AntiProxy releases on GitHub. Unlike app store updates, " +
                        "it isn't reviewed by a store. Only continue if you trust The 3AM Org's releases."
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = neverAgain, role = Role.Checkbox, onValueChange = { neverAgain = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = neverAgain, onCheckedChange = null)
                    Text("Don't show this again", modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(neverAgain) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Every release up to the installed one, newest first, with its notes. */
@Composable
fun ChangelogBottomSheet(
    releases: List<Release>,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
                    Text(
                        "Changelog",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                item {
                    LinearWavyProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                }
                when {
                    loading -> item {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    releases.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                            EmptyLine(
                                if (failed) "Couldn't load the changelog. Make sure you're online."
                                else "No release notes yet."
                            )
                        }
                    }
                    else -> items(releases, key = { it.tagName }) { ReleaseNotes(it) }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { context.openUrl(Links.RELEASES) },
                icon = { Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(24.dp)) },
                text = { Text("View on GitHub") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .semantics { contentDescription = "View releases on GitHub" }
            )
        }
    }
}

@Composable
private fun ReleaseNotes(release: Release) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Text(
                    release.tagName + if (release.prerelease) " · pre-release" else "",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val date = release.publishedAt.substringBefore("T")
            if (date.isNotBlank()) Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                if (release.notes.isNotBlank()) MarkdownText(release.notes) else EmptyLine("No notes for this release.")
            }
        }
    }
}

private val linkOrMention = Regex("(@[a-zA-Z0-9_-]+)|(https?://[\\w-]+(\\.[\\w-]+)+[\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])")
private val bold = Regex("\\*\\*(.*?)\\*\\*")

/** Enough Markdown for release notes: # headings, - / * bullets, **bold**, links and @mentions. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                Text(
                    line.drop(level).trim(),
                    style = when (level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() }
                )
            } else {
                val bullet = line.startsWith("- ") || line.startsWith("* ")
                val content = if (bullet) line.drop(2).trim() else line
                val annotated = buildAnnotatedString {
                    var last = 0
                    linkOrMention.findAll(content).forEach { m ->
                        appendBold(content.substring(last, m.range.first))
                        val url = if (m.value.startsWith("@")) "https://github.com/${m.value.drop(1)}" else m.value
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(
                                        color = primary,
                                        fontWeight = if (m.value.startsWith("@")) FontWeight.Bold else FontWeight.Normal,
                                        textDecoration = if (m.value.startsWith("@")) TextDecoration.None else TextDecoration.Underline
                                    )
                                )
                            )
                        ) { append(m.value) }
                        last = m.range.last + 1
                    }
                    appendBold(content.substring(last))
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (bullet) Text("•", modifier = Modifier.padding(end = 8.dp), color = primary)
                        Text(annotated, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (bullet) {
                        HorizontalDivider(
                            Modifier.padding(top = 4.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendBold(text: String) {
    var cursor = 0
    bold.findAll(text).forEach { m ->
        append(text.substring(cursor, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        cursor = m.range.last + 1
    }
    append(text.substring(cursor))
}
