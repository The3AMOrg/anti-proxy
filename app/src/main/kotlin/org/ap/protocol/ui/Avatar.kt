package org.ap.protocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

// Dark enough for white initials (contrast ≥ 4.5:1)
private val letterColors = listOf(
    Color(0xFF0B57D0), Color(0xFF00695C), Color(0xFF6A1B9A), Color(0xFFAD1457),
    Color(0xFF2E7D32), Color(0xFF5D4037), Color(0xFFBF360C), Color(0xFF3949AB)
)

/**
 * Google account photo in a circle. The first letter of the name shows until the photo loads,
 * and stays if there is none or it fails. Decorative: the name is always shown next to it.
 */
@Composable
fun Avatar(name: String, photoUrl: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    // Sized to the circle, not the font scale, so a large font can't overflow it
    val fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(letterColors[Math.floorMod(name.hashCode(), letterColors.size)]),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Medium)
        if (photoUrl.isNotEmpty()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
