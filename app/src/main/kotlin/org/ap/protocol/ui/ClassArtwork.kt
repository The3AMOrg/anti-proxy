@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package org.ap.protocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import org.ap.protocol.classroom.CLASS_THEMES

private class ArtTheme(
    val label: String,
    val base: Color,
    val accent: Color,
    val icon: ImageVector,
    val big: RoundedPolygon,
    val small: RoundedPolygon
)

// Bases are dark enough for white text (contrast ≥ 4.5:1). Order is stored in Firestore: append only.
private val themes = listOf(
    ArtTheme("Science", Color(0xFF00695C), Color(0xFF4DB6AC), Icons.Rounded.Science, MaterialShapes.Cookie9Sided, MaterialShapes.Pill),
    ArtTheme("Maths", Color(0xFF3949AB), Color(0xFF7986CB), Icons.Rounded.Calculate, MaterialShapes.Sunny, MaterialShapes.Diamond),
    ArtTheme("Computing", Color(0xFF0B57D0), Color(0xFF7CACF8), Icons.Rounded.Computer, MaterialShapes.Clover4Leaf, MaterialShapes.Gem),
    ArtTheme("Languages", Color(0xFFBF360C), Color(0xFFFF8A65), Icons.Rounded.Translate, MaterialShapes.Flower, MaterialShapes.Circle),
    ArtTheme("Arts", Color(0xFFAD1457), Color(0xFFF06292), Icons.Rounded.Palette, MaterialShapes.SoftBurst, MaterialShapes.Heart),
    ArtTheme("Music", Color(0xFF6A1B9A), Color(0xFFBA68C8), Icons.Rounded.MusicNote, MaterialShapes.Cookie12Sided, MaterialShapes.Arch),
    ArtTheme("Sports", Color(0xFF2E7D32), Color(0xFF81C784), Icons.Rounded.SportsSoccer, MaterialShapes.Burst, MaterialShapes.Pentagon),
    ArtTheme("History", Color(0xFF5D4037), Color(0xFFA1887F), Icons.Rounded.HistoryEdu, MaterialShapes.Puffy, MaterialShapes.Bun)
).also { check(it.size == CLASS_THEMES) }

/**
 * A class's header: theme colour, two Expressive shapes and a subject icon, scaled to the box.
 * Text goes in [content], on the left, where the shapes don't reach.
 */
@Composable
fun ClassArtwork(theme: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val t = themes[theme.coerceIn(themes.indices)]
    BoxWithConstraints(modifier.clip(MaterialTheme.shapes.extraLarge).background(t.base)) {
        // In a list the height is unbounded (it grows with the text), so size the shapes to the minimum
        val h = if (constraints.hasBoundedHeight) maxHeight else minHeight.coerceAtLeast(96.dp)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = h * 0.25f, y = -h * 0.2f)
                .size(h * 1.1f)
                .rotate(15f)
                .clip(t.big.toShape())
                .background(t.accent.copy(alpha = 0.45f))
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = -h * 0.75f, y = h * 0.2f)
                .size(h * 0.45f)
                .rotate(-20f)
                .clip(t.small.toShape())
                .background(t.accent.copy(alpha = 0.35f))
        )
        Icon(
            t.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = h * 0.18f)
                .size(h * 0.36f)
        )
        content()
    }
}

/** Artwork choices for the create / edit form. TalkBack reads them as radio buttons. */
@Composable
fun ThemePicker(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        themes.forEachIndexed { i, t ->
            val isSelected = i == selected
            ClassArtwork(
                theme = i,
                modifier = Modifier
                    .size(width = 104.dp, height = 60.dp)
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                        else Modifier
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { contentDescription = "${t.label} artwork" }
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                    )
                }
            }
        }
    }
}
