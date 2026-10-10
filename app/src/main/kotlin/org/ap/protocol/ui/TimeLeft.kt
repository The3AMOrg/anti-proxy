@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.ap.protocol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Time left in a session (Unix seconds). The bar and clock tick every second, but TalkBack hears
 * it once a minute ("4 minutes left"), so it is announced without flooding the screen reader.
 */
@Composable
fun TimeLeft(issuedAt: Long, expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val left = (expiresAt - now).coerceAtLeast(0)
    val total = (expiresAt - issuedAt).coerceAtLeast(1)
    val minutes = (left + 59) / 60
    val spoken = when {
        left == 0L -> "Time is up"
        minutes == 1L -> "Less than a minute left"
        else -> "$minutes minutes left"
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        LinearWavyProgressIndicator(
            progress = { left.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "%d:%02d left".format(left / 60, left % 60),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
