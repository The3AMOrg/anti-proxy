package org.ap.protocol.ui

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when "Remove animations" is on in Settings > Accessibility (it sets the animator scale to 0). */
@Composable
fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/**
 * Google Pay style "done" check: a circle pops in, then the tick draws itself.
 * With reduced motion it appears already finished.
 */
@Composable
fun CheckBadge(color: Color, tick: Color, size: Dp, instant: Boolean) {
    val pop = remember { Animatable(if (instant) 1f else 0f) }
    val draw = remember { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!instant) {
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            draw.animateTo(1f, tween(durationMillis = 350))
        }
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color, radius = w / 2 * pop.value.coerceAtLeast(0f), center = Offset(w / 2, h / 2))

        // The tick is two strokes: the short one first (40% of the time), then the long one.
        val a = Offset(w * 0.27f, h * 0.52f)
        val b = Offset(w * 0.45f, h * 0.68f)
        val c = Offset(w * 0.74f, h * 0.34f)
        val stroke = w * 0.09f
        val first = (draw.value / 0.4f).coerceIn(0f, 1f)
        val second = ((draw.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
        drawLine(tick, a, lerp(a, b, first), strokeWidth = stroke, cap = StrokeCap.Round)
        if (second > 0f) drawLine(tick, b, lerp(b, c, second), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

// Screen transitions, the same springs as AttendX's navigation: a short slide plus a fade, and the
// screen being left behind on Back shrinks a little, so the motion reads as going back.
private fun <T> nav() = spring<T>(stiffness = Spring.StiffnessMediumLow)
private fun navOffset() = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

fun slideFadeInFromRight(): EnterTransition =
    slideInHorizontally(navOffset()) { (it * 0.15f).toInt() } + fadeIn(nav(), initialAlpha = 0.5f)

fun slideFadeOutToLeft(): ExitTransition =
    slideOutHorizontally(navOffset()) { -(it * 0.10f).toInt() } + fadeOut(nav())

fun slideFadeInFromLeft(): EnterTransition =
    slideInHorizontally(navOffset()) { -(it * 0.15f).toInt() } + fadeIn(nav(), initialAlpha = 0.5f)

fun slideFadeOutToRight(): ExitTransition =
    slideOutHorizontally(navOffset()) { (it * 0.10f).toInt() } + fadeOut(nav()) + scaleOut(nav(), targetScale = 0.95f)
