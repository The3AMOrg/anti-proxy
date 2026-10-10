@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.ap.protocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ap.protocol.account.AppRole

@Composable
private fun OnboardingFrame(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) { content() }
        }
    }
}

@Composable
fun SignInScreen(
    signingIn: Boolean,
    error: String?,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onCelebrated: () -> Unit
) {
    val instant = reduceMotion()
    // Once signed in, the shield shows a check for a moment, then the app moves on
    LaunchedEffect(signedIn) {
        if (signedIn) {
            delay(if (instant) 800L else 1500L)
            onCelebrated()
        }
    }
    OnboardingFrame {
        // Decorative Expressive shape behind the app mark
        Box(
            Modifier
                .padding(top = 48.dp)
                .size(128.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (signedIn) {
                CheckBadge(
                    color = MaterialTheme.colorScheme.primary,
                    tick = MaterialTheme.colorScheme.onPrimary,
                    size = 72.dp,
                    instant = instant
                )
            } else {
                Icon(
                    Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        Text(
            "AntiProxy",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            "Attendance that can't be faked. Sign in so your classrooms stay in sync on every phone.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (signedIn) {
            Text("Signed in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (signingIn) {
            ContainedLoadingIndicator(Modifier.size(64.dp))
        } else {
            ActionButton(
                text = "Continue with Google",
                icon = Icons.AutoMirrored.Rounded.Login,
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
fun RoleScreen(name: String?, onChoose: (AppRole) -> Unit) {
    OnboardingFrame {
        Text(
            if (name != null) "Welcome, ${name.substringBefore(' ')}" else "Welcome",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 48.dp).semantics { heading() }
        )
        Text(
            "How will you use AntiProxy? You can change this later.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RoleCard(
            title = "I'm a teacher",
            detail = "Create classrooms and take attendance.",
            icon = Icons.Rounded.CoPresent,
            onClick = { onChoose(AppRole.Instructor) }
        )
        RoleCard(
            title = "I'm a student",
            detail = "Join classrooms and mark your attendance.",
            icon = Icons.Rounded.School,
            onClick = { onChoose(AppRole.Student) }
        )
    }
}

@Composable
private fun RoleCard(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(MaterialShapes.Sunny.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
