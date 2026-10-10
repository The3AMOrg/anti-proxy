@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package org.ap.protocol.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import org.ap.protocol.update.UpdateSettings
import org.ap.protocol.update.versionLabel
import org.ap.protocol.account.AppRole
import org.ap.protocol.account.ThemeMode

@Composable
fun SettingsScreen(
    name: String,
    email: String,
    photoUrl: String,
    role: AppRole,
    themeMode: ThemeMode,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSignOut: () -> Unit,
    onRole: (AppRole) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    updates: UpdateSettings,
    checkingForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onUpdateSettings: ((UpdateSettings) -> UpdateSettings) -> Unit,
    onAbout: () -> Unit,
    onChangelog: () -> Unit
) {
    // System Back is handled app-wide by the predictive back handler in MainActivity
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Account") {
                val identity: @Composable () -> Unit = {
                    Column {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Large text: photo above, so the name and email get the full width
                if (LocalDensity.current.fontScale >= 1.3f) {
                    Avatar(name, photoUrl, size = 64.dp)
                    identity()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Avatar(name, photoUrl, size = 64.dp)
                        identity()
                    }
                }
                EmptyLine("Your photo comes from your Google account. Your name is shown on class rosters.")
                ActionButton("Edit name", Icons.Rounded.Edit, onClick = { renaming = true }, kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth())
                ActionButton("Sign out", Icons.AutoMirrored.Rounded.Logout, onClick = onSignOut, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
            }

            SectionCard("Use AntiProxy as") {
                ChoiceGroup(
                    options = listOf(
                        Choice("Teacher", Icons.Rounded.CoPresent),
                        Choice("Student", Icons.Rounded.School)
                    ),
                    selected = if (role == AppRole.Instructor) 0 else 1,
                    onSelect = { onRole(if (it == 0) AppRole.Instructor else AppRole.Student) }
                )
                EmptyLine(
                    if (role == AppRole.Instructor) "You see the classes you teach and can take attendance."
                    else "You see the classes you joined and can mark your attendance."
                )
            }

            SectionCard("Theme") {
                ChoiceGroup(
                    options = listOf(
                        Choice("System", Icons.Rounded.PhoneAndroid),
                        Choice("Light", Icons.Rounded.LightMode),
                        Choice("Dark", Icons.Rounded.DarkMode)
                    ),
                    selected = themeMode.ordinal,
                    onSelect = { onTheme(ThemeMode.entries[it]) }
                )
            }

            BluetoothCard()

            UpdatesCard(updates, checkingForUpdates, onCheckForUpdates, onUpdateSettings)

            SectionCard("About") {
                NavRow(Icons.Rounded.Info, "About AntiProxy", "The 3AM Org, developers, links", onAbout)
                NavRow(Icons.Rounded.NewReleases, "Changelog", "What changed in each release", onChangelog)
            }
        }
    }

    if (renaming) {
        TextEntryDialog(
            title = "Your name",
            fieldLabel = "Shown on class rosters",
            confirmLabel = "Save",
            initial = name,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                onRename(it)
            }
        )
    }
}

class Choice(val label: String, val icon: ImageVector)

/**
 * One choice from a few: a connected button group, read by TalkBack as radio buttons.
 * At large font sizes the buttons stack so labels never break mid-word.
 */
@Composable
fun ChoiceGroup(options: List<Choice>, selected: Int, onSelect: (Int) -> Unit) {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    // Three or more side by side leave no room for icons next to the labels
    val showIcons = stacked || options.size <= 2
    // Unselected buttons sit on a card, so they need their own container to read as buttons
    val colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    val button: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        val item = options[index]
        ToggleButton(
            checked = index == selected,
            onCheckedChange = { onSelect(index) },
            shapes = when {
                stacked -> ToggleButtonDefaults.shapes()
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            },
            colors = colors,
            modifier = modifier
                .heightIn(min = 56.dp)
                .semantics { role = Role.RadioButton }
        ) {
            if (showIcons) {
                Icon(item.icon, contentDescription = null)
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
            }
            Text(item.label, maxLines = 1)
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.indices.forEach { button(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            options.indices.forEach { button(it, Modifier.weight(1f)) }
        }
    }
}

/** What attendance needs, each with a one-tap fix. Re-checked whenever the screen comes back. */
@Composable
private fun BluetoothCard() {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val bluetoothOn = remember(checks) {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }
    val nearbyPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val allowed = remember(checks) {
        nearbyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    // After a refusal Android may stop showing the prompt, so the next tap opens the app's settings page
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checks++
        refused = !result.values.all { it }
    }

    SectionCard("Bluetooth and permissions") {
        StatusRow(
            ok = bluetoothOn,
            okIcon = Icons.Rounded.Bluetooth,
            badIcon = Icons.Rounded.BluetoothDisabled,
            text = if (bluetoothOn) "Bluetooth is on." else "Bluetooth is off. Attendance needs it.",
            fixLabel = "Turn on",
            onFix = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        )
        StatusRow(
            ok = allowed,
            okIcon = Icons.Rounded.CheckCircle,
            badIcon = Icons.Rounded.ErrorOutline,
            text = if (allowed) "Nearby devices is allowed." else "Nearby devices isn't allowed, so AntiProxy can't find your teacher's phone.",
            fixLabel = "Allow",
            onFix = {
                if (refused) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    )
                } else {
                    ask.launch(nearbyPermissions)
                }
            }
        )
    }
}

@Composable
private fun StatusRow(ok: Boolean, okIcon: ImageVector, badIcon: ImageVector, text: String, fixLabel: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (ok) okIcon else badIcon,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) FilledTonalButton(onClick = onFix) { Text(fixLabel) }
    }
}

@Composable
private fun UpdatesCard(
    settings: UpdateSettings,
    checking: Boolean,
    onCheck: () -> Unit,
    onChange: ((UpdateSettings) -> UpdateSettings) -> Unit
) {
    SectionCard("Updates") {
        Text("You have AntiProxy ${versionLabel()}", style = MaterialTheme.typography.bodyLarge)
        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(40.dp))
                Text("Checking GitHub for a newer version…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        } else {
            ActionButton("Check for updates", Icons.Rounded.Update, onClick = onCheck, kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth())
        }
        SwitchRow("Check when the app opens", "Shows the update sheet if a newer version is out", settings.checkOnStart) { on ->
            onChange { it.copy(checkOnStart = on) }
        }
        SwitchRow("Notify me about new versions", "Checks once a day in the background", settings.notify) { on ->
            onChange { it.copy(notify = on) }
        }
        SwitchRow("Include pre-releases", "Get test versions before they're final", settings.includePrerelease) { on ->
            onChange { it.copy(includePrerelease = on) }
        }
        SwitchRow("Download inside the app", "Off: opens the release page on GitHub instead", settings.directDownload) { on ->
            onChange { it.copy(directDownload = on) }
        }
        SwitchRow("Warn before downloading", "A reminder that updates come from GitHub, not a store", settings.warnBeforeDownload) { on ->
            onChange { it.copy(warnBeforeDownload = on) }
        }
    }
}

/** The whole row toggles, so the target is the full width; TalkBack reads it as one switch. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
}
