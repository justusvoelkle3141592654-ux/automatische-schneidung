package de.lumen.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SquircleShape
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.IconImage
import de.lumen.launcher.data.apps.IconSpec
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.graph
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlin.random.Random
import androidx.compose.ui.platform.LocalContext

val LocalIconSpec = staticCompositionLocalOf { IconSpec(180) }
val LocalApps = staticCompositionLocalOf<Map<AppKey, AppInfo>> { emptyMap() }
val LocalController = staticCompositionLocalOf<LauncherController> { error("No LauncherController") }

@Composable
fun rememberAppIcon(app: AppInfo?): IconImage? {
    if (app == null) return null
    val ctx = LocalContext.current
    val spec = LocalIconSpec.current
    val loader = ctx.graph.icons
    val icon by produceState(loader.peek(app.key, spec), app.key, spec) {
        value = loader.load(app, spec)
    }
    return icon
}

/** Draws a rendered icon; "clear" icons get a glass plate behind the glyph. */
@Composable
fun IconImageView(icon: IconImage?, size: Dp, modifier: Modifier = Modifier, sampleWallpaper: Boolean = true) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            icon == null -> Box(
                Modifier
                    .fillMaxSize()
                    .glass(SquircleShape, GlassStyle(elevation = 0f, sampleWallpaper = sampleWallpaper))
            )
            icon.glassPlate -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .glass(SquircleShape, GlassStyle(elevation = 0.4f, sampleWallpaper = sampleWallpaper, tint = Color.White.copy(alpha = 0.14f)))
                )
                Image(icon.bitmap, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.Medium)
            }
            else -> Image(icon.bitmap, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.Medium)
        }
    }
}

/** Jiggle animation of edit mode: each icon gets its own phase so the screen feels alive. */
@Composable
fun Modifier.jiggle(enabled: Boolean, seed: Long): Modifier {
    if (!enabled || !Lumen.motion.enabled) return this
    val rnd = remember(seed) { Random(seed) }
    val duration = remember(seed) { 230 + rnd.nextInt(70) }
    val transition = rememberInfiniteTransition(label = "jiggle")
    val rot by transition.animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
        label = "rot",
    )
    val dy by transition.animateFloat(
        initialValue = -0.6f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(duration + 40, easing = LinearEasing), RepeatMode.Reverse),
        label = "dy",
    )
    return graphicsLayer { rotationZ = rot; translationY = dy * density }
}

/** Pressed-state feedback: scale down + slight darkening, springy release. */
@Composable
fun Modifier.pressFeedback(pressed: Boolean, scale: Float = 0.88f): Modifier {
    val s by animateFloatAsState(if (pressed) scale else 1f, Lumen.motion.bouncy(), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * A home screen tile (app or folder) with label, press feedback, jiggle, remove badge and
 * gesture plumbing for context menu and drag & drop.
 */
@Composable
fun HomeTile(
    label: String?,
    iconSize: Dp,
    onTap: (bounds: Rect) -> Unit,
    onLongPress: (bounds: Rect, down: Offset) -> Unit,
    onEditPress: ((bounds: Rect, down: Offset) -> Unit)?,
    modifier: Modifier = Modifier,
    jiggle: Boolean = false,
    seed: Long = 0,
    hidden: Boolean = false,
    highlighted: Boolean = false,
    work: Boolean = false,
    onRemove: (() -> Unit)? = null,
    labelColor: Color? = null,
    icon: @Composable (Modifier) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var iconCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val hl by animateFloatAsState(if (highlighted) 1.12f else 1f, Lumen.motion.bouncy(), label = "hl")
    val settings = Lumen.settings
    Column(
        modifier = modifier.graphicsLayer { alpha = if (hidden) 0f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .jiggle(jiggle, seed)
                .graphicsLayer { scaleX = hl; scaleY = hl }
                .pressFeedback(pressed)
                .onGloballyPositioned { iconCoords = it }
                .pointerInput(onEditPress != null) {
                    detectTapGestures(
                        onPress = { pos ->
                            pressed = true
                            val c = iconCoords
                            if (onEditPress != null && c != null && c.isAttached) {
                                onEditPress(c.boundsInRoot(), c.positionInRoot() + pos)
                            }
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { iconCoords?.takeIf { it.isAttached }?.let { onTap(it.boundsInWindow()) } },
                        onLongPress = { pos ->
                            pressed = false
                            iconCoords?.takeIf { it.isAttached }?.let { onLongPress(it.boundsInRoot(), it.positionInRoot() + pos) }
                        },
                    )
                },
        ) {
            icon(Modifier.size(iconSize))
            if (work) WorkBadge(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp))
            if (onRemove != null && jiggle) {
                RemoveBadge(onRemove, Modifier.align(Alignment.TopStart).offset((-7).dp, (-7).dp))
            }
        }
        if (label != null && settings.showLabels) {
            Spacer(Modifier.height(5.dp))
            BasicText(
                label,
                style = (if (labelColor != null) LumenType.iconLabel.copy(color = labelColor, shadow = null) else LumenType.iconLabel).copy(
                    fontSize = LumenType.iconLabel.fontSize * settings.labelScale,
                    lineHeight = LumenType.iconLabel.lineHeight * settings.labelScale,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(iconSize + 18.dp),
            )
        }
    }
}

@Composable
fun RemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .glass(CircleShape, GlassStyle(tint = Color(0xCC3A3A3C), elevation = 0.5f, sampleWallpaper = false))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Remove, "Entfernen", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun WorkBadge(modifier: Modifier) {
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(0xFFFF9F0A)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Work, "Arbeitsprofil", tint = Color.White, modifier = Modifier.size(11.dp))
    }
}

/** Folder icon: a glass squircle with a 3×3 preview of its apps (iOS style). */
@Composable
fun FolderIcon(folder: FolderItem, size: Dp, modifier: Modifier = Modifier, sampleWallpaper: Boolean = true) {
    val apps = LocalApps.current
    val previews = folder.apps.take(9).mapNotNull { apps[it] }
    val pad = size * 0.13f
    val gap = size * 0.06f
    val mini = (size - pad * 2 - gap * 2) / 3
    Box(
        modifier
            .size(size)
            .glass(SquircleShape, GlassStyle(tint = Color.White.copy(alpha = 0.30f), elevation = 0.3f, sampleWallpaper = sampleWallpaper)),
    ) {
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (c in 0 until 3) {
                        val app = previews.getOrNull(r * 3 + c)
                        if (app != null) {
                            val icon = rememberAppIcon(app)
                            IconImageView(icon, mini, sampleWallpaper = false)
                        } else {
                            Spacer(Modifier.size(mini))
                        }
                    }
                }
            }
        }
    }
}

/** Plays a one-off "pop" when [trigger] changes (e.g. app added to the dock). */
@Composable
fun Modifier.popOnChange(trigger: Any?): Modifier {
    val scale = remember { Animatable(1f) }
    val motion = Lumen.motion
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (trigger != null) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, motion.bouncy())
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** Darkens content slightly (used for the pressed state of widgets). */
fun Modifier.dimmed(amount: Float): Modifier = if (amount <= 0f) this else drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = amount))
}
