@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package org.ap.protocol.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ap.protocol.BuildConfig
import org.ap.protocol.R
import org.ap.protocol.update.Links
import org.ap.protocol.update.deviceAbi
import org.ap.protocol.update.openUrl
import org.ap.protocol.update.versionLabel

/** The people behind AntiProxy. Listed as equals: same card, same size, same role. */
private class Developer(val name: String, val handle: String, val website: String) {
    val github get() = "https://github.com/$handle"
    val avatar get() = "https://github.com/$handle.png"
}

private val developers = listOf(
    Developer("Jyotirmoy Das", "JyotirmoyDas05", "https://jyotimoydas.in"),
    Developer("Ronit Choudhury", "nonQualities", "https://nonqualities.github.io/me/")
)

/** AttendX's About screen, for AntiProxy under The 3AM Org. */
@Composable
fun AboutScreen(onBack: () -> Unit, onChangelog: () -> Unit, onCheckForUpdates: () -> Unit) {
    // System Back is handled app-wide by the predictive back handler in MainActivity
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { AppHeaderCard() }
            item { OrgCard(onOpen = { context.openUrl(Links.ORG) }) }
            item { DevelopersSection() }
            item {
                LinkGroup(
                    title = "Community & info",
                    items = listOf(
                        LinkItem(Icons.Rounded.Update, null, "Check for updates", "See if a newer version is out", onCheckForUpdates),
                        LinkItem(Icons.Rounded.NewReleases, null, "Changelog", "What changed in each release", onChangelog),
                        LinkItem(Icons.Outlined.BugReport, null, "Report a bug", "Open an issue or ask for a feature on GitHub") {
                            context.openUrl(Links.ISSUES_NEW)
                        },
                        LinkItem(null, painterResource(R.drawable.ic_github), "GitHub repository", Links.REPO.removePrefix("https://")) {
                            context.openUrl(Links.REPO)
                        }
                    )
                )
            }
            item { OrgQuote() }
        }
    }
}

@Composable
private fun AppHeaderCard() {
    ElevatedCard(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            // Placeholder mark until the app icon is designed
            Box(
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    "AntiProxy",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "Attendance that can't be faked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(versionLabel(), MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.primary)
                    Chip(deviceAbi().uppercase(), MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.onSecondaryContainer)
                    if (BuildConfig.DEBUG) {
                        Chip("DEBUG", MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun OrgCard(onOpen: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("A project by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = Links.ORG_AVATAR,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
                Column {
                    Text("The 3AM Org", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("github.com/The3AMOrg", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("The 3AM Org on GitHub", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Side by side at normal text size; stacked when large text would squeeze two cards. */
@Composable
private fun DevelopersSection() {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Developers",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() }
        )
        if (stacked) {
            developers.forEach { DeveloperCard(it, Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                developers.forEach { DeveloperCard(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DeveloperCard(dev: Developer, modifier: Modifier) {
    val context = LocalContext.current
    ElevatedCard(shape = RoundedCornerShape(32.dp), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MorphingProfileAvatar(avatarUrl = dev.avatar, avatarSize = 88.dp)
            Spacer(Modifier.height(8.dp))
            Text(dev.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(
                "@${dev.handle}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Developer", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { context.openUrl(dev.github) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_github), contentDescription = "${dev.name} on GitHub", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(onClick = { context.openUrl(dev.website) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Language, contentDescription = "${dev.name}'s website")
                }
            }
        }
    }
}

private class LinkItem(
    val vector: ImageVector?,
    val painter: Painter?,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

/** AttendX's settings group: connected rounded rows, large corners at the ends. */
@Composable
private fun LinkGroup(title: String, items: List<LinkItem>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp).semantics { heading() }
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, item ->
                val shape = when {
                    items.size == 1 -> RoundedCornerShape(24.dp)
                    i == 0 -> RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp)
                    i == items.lastIndex -> RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp)
                    else -> RoundedCornerShape(6.dp)
                }
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val tint = MaterialTheme.colorScheme.primary
                            if (item.vector != null) Icon(item.vector, contentDescription = null, tint = tint)
                            else if (item.painter != null) Icon(item.painter, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgQuote() {
    val quote = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 20.sp)) { append("“") }
        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)) {
            append(" A org by insomniacs, and one early sleeper ")
        }
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 20.sp)) { append("”") }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(quote, textAlign = TextAlign.Center)
        Text("— The 3AM Org", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

private const val QuarterRotation = 90f
private const val FullRotation = 360f
private const val MorphIntervalMillis = 650L
private const val GlobalRotationMillis = 4666

/**
 * AttendX's avatar: the photo inside Expressive loading-indicator shapes that morph and turn.
 * Holds a single still shape when "Remove animations" is on.
 */
@Composable
fun MorphingProfileAvatar(avatarUrl: String, avatarSize: Dp, modifier: Modifier = Modifier) {
    val still = reduceMotion()
    val polygons = remember { LoadingIndicatorDefaults.IndeterminateIndicatorPolygons }
    val morphs = remember(polygons) { List(polygons.size) { i -> Morph(polygons[i], polygons[(i + 1) % polygons.size]) } }
    val morphProgress = remember { Animatable(0f) }
    var rotationTarget by remember { mutableFloatStateOf(QuarterRotation) }
    val globalRotation = remember { Animatable(0f) }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        launch {
            val spec = spring<Float>(dampingRatio = 0.6f, stiffness = 200f, visibilityThreshold = 0.05f)
            while (true) {
                val step = async { morphProgress.animateTo(1f, spec) }
                delay(MorphIntervalMillis)
                if (step.await().endReason == AnimationEndReason.Finished) {
                    index = (index + 1) % morphs.size
                    morphProgress.snapTo(0f)
                    rotationTarget = (rotationTarget + QuarterRotation) % FullRotation
                }
            }
        }
        launch {
            globalRotation.animateTo(
                FullRotation,
                infiniteRepeatable(tween(GlobalRotationMillis, easing = LinearEasing), RepeatMode.Restart)
            )
        }
    }

    val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    Box(
        modifier
            .size(avatarSize)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val progress = morphProgress.value.coerceIn(0f, 1f)
                val path = morphs[index].toPath(progress = progress).asComposePath()
                val bounds = path.getBounds()
                val target = minOf(size.width, size.height) * 0.9f
                val scale = if (bounds.width > 0f && bounds.height > 0f) target / maxOf(bounds.width, bounds.height) else 1f
                path.transform(Matrix().apply {
                    translate(size.width / 2f, size.height / 2f)
                    scale(scale, scale)
                    translate(-bounds.left - bounds.width / 2f, -bounds.top - bounds.height / 2f)
                })
                val angle = progress * QuarterRotation + rotationTarget + globalRotation.value
                rotate(angle) { drawPath(path, Color.Black) }
                drawContent()
                rotate(angle) { drawPath(path, border, style = Stroke(width = 2.5.dp.toPx())) }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.SrcIn }
        )
    }
}
