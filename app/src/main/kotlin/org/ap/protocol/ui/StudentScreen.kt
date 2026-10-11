@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.ap.protocol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import antiproxy.core.protocol.StatusCode
import antiproxy.core.storage.AttendanceRecord
import org.ap.protocol.flow.StudentCheckInState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Announced politely by TalkBack as the check-in progresses. */
@Composable
fun CheckInStatus(state: StudentCheckInState) {
    val (icon, text, color) = describe(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.isBusy) {
            LoadingIndicator(Modifier.size(40.dp))
        } else {
            Icon(icon, contentDescription = null, tint = color)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

private data class StatusView(val icon: ImageVector, val text: String, val color: Color)

@Composable
private fun describe(state: StudentCheckInState): StatusView {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    return when (state) {
        StudentCheckInState.Idle ->
            StatusView(Icons.Rounded.Info, "Tap Check in once your teacher starts a session.", neutral)
        StudentCheckInState.Scanning ->
            StatusView(Icons.Rounded.Info, "Looking for your teacher's phone…", neutral)
        is StudentCheckInState.Discovered ->
            StatusView(Icons.Rounded.Info, "Found your teacher. Measuring distance (${state.sampleCount} samples)…", neutral)
        StudentCheckInState.Connecting ->
            StatusView(Icons.Rounded.Info, "Connecting…", neutral)
        StudentCheckInState.VerifyingSession ->
            StatusView(Icons.Rounded.Info, "Checking the session is genuine…", neutral)
        StudentCheckInState.SigningProof ->
            StatusView(Icons.Rounded.Info, "Signing your attendance…", neutral)
        StudentCheckInState.Submitting ->
            StatusView(Icons.Rounded.Info, "Sending to your teacher…", neutral)
        is StudentCheckInState.Success ->
            StatusView(Icons.Rounded.CheckCircle, "Attendance marked.", MaterialTheme.colorScheme.primary)
        is StudentCheckInState.Rejected ->
            StatusView(Icons.Rounded.ErrorOutline, rejectionMessage(state.status), error)
        is StudentCheckInState.Error ->
            StatusView(Icons.Rounded.ErrorOutline, state.message, error)
    }
}

/** Plain-language reasons instead of protocol code names. Exhaustive, so new codes must be worded. */
private fun rejectionMessage(status: StatusCode): String = when (status) {
    StatusCode.SUCCESS -> "Attendance marked."
    StatusCode.UNKNOWN_SESSION, StatusCode.SESSION_EXPIRED ->
        "This session isn't running any more. Ask your teacher to start it again."
    StatusCode.NONCE_MISMATCH -> "The session changed while you were checking in. Try again."
    StatusCode.UNKNOWN_DEVICE ->
        "Your teacher's phone doesn't have you on this class's list. If you just joined, ask them to open AntiProxy while online."
    StatusCode.DEVICE_REVOKED -> "Your teacher has removed this phone."
    StatusCode.INVALID_SIGNATURE -> "Your attendance couldn't be verified. Try again."
    StatusCode.REPLAY, StatusCode.DUPLICATE_ATTENDANCE -> "You're already marked present for this session."
    StatusCode.INVALID_RSSI_EVIDENCE, StatusCode.POLICY_REJECTED ->
        "You seem too far from your teacher. Move closer and try again."
    StatusCode.TRUST_LEASE_EXPIRED -> "Your teacher's app needs to go online to refresh. Ask them to reconnect."
    StatusCode.MALFORMED_FRAME, StatusCode.MTU_OR_FRAGMENT_ERROR -> "The connection was interrupted. Try again."
    StatusCode.UNSUPPORTED_VERSION -> "You and your teacher are on different app versions. Update the app."
    StatusCode.INTERNAL_STORAGE_FAILURE -> "Your teacher's phone couldn't save it. Try again."
}
