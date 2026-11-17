package de.lumen.launcher.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.launch

// ============================================================================ liquid touch response

/**
 * iOS 26 style touch response for glass controls: the surface springs in slightly and a soft
 * light blooms from the finger position, fading out on release.
 */
@Composable
fun Modifier.liquidPress(
    enabled: Boolean = true,
    pressScale: Float = 0.96f,
    shape: Shape = RoundedCornerShape(50),
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val glow = remember { Animatable(0f) }
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) pressScale else 1f, Lumen.motion.bouncy(), label = "liquid")
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            val g = glow.value
            if (g > 0.001f && touch != Offset.Unspecified) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent))
                }
                clipPath(path) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.28f * g), Color.Transparent),
                            center = touch,
                            radius = size.maxDimension * 0.75f,
                        )
                    )
                }
            }
        }
        .pointerInput(enabled, onLongPress != null) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = { pos ->
                    touch = pos
                    pressed = true
                    scope.launch { glow.animateTo(1f, androidx.compose.animation.core.tween(120)) }
                    tryAwaitRelease()
                    pressed = false
                    scope.launch { glow.animateTo(0f, androidx.compose.animation.core.tween(380)) }
                },
                onTap = { click() },
                onLongPress = long?.let { l -> { _: Offset -> l() } },
            )
        }
}

// ============================================================================ surfaces

/** Glass card on top of an already blurred overlay (control center tiles, library groups…). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = SmoothRoundedShape(22.dp),
    tint: Color? = null,
    sampleWallpaper: Boolean = false,
    elevation: Float = 0.6f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, GlassStyle(tint = tint ?: Lumen.colors.glassTint, sampleWallpaper = sampleWallpaper, elevation = elevation)), content = content)
}

/** Round glass icon button. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    activeColor: Color = Lumen.colors.accent,
    sampleWallpaper: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Lumen.colors
    val bg by animateColorAsState(if (active) activeColor else colors.glassTint, Lumen.motion.fade(), label = "btn")
    Box(
        modifier
            .size(size)
            .glass(CircleShape, GlassStyle(tint = bg, sampleWallpaper = sampleWallpaper, elevation = 0.5f))
            .clip(CircleShape)
            .liquidPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (active) Color.White else colors.textPrimary, modifier = Modifier.size(size * 0.48f))
    }
}

/** Capsule text button ("Fertig", "Erlauben"…). */
@Composable
fun GlassCapsuleButton(
    text: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = Lumen.colors
    Row(
        modifier
            .height(40.dp)
            .glass(CircleShape, GlassStyle(tint = if (prominent) colors.accent else colors.glassTint, sampleWallpaper = false, elevation = 0.5f))
            .clip(CircleShape)
            .liquidPress(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (prominent) Color.White else colors.textPrimary
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        BasicText(text, style = LumenType.headline.copy(color = fg, fontSize = LumenType.subhead.fontSize))
    }
}

// ============================================================================ controls

/** iOS style switch. */
@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Lumen.colors
    val track by animateColorAsState(if (checked) colors.success else colors.fillStrong, Lumen.motion.fade(), label = "track")
    val x by animateFloatAsState(if (checked) 1f else 0f, Lumen.motion.spring(0.7f, 500f), label = "thumb")
    Box(
        modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(track)
            .pointerInput(checked) { detectTapGestures { onChange(!checked) } }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = 20.dp * x)
                .size(27.dp)
                .glass(CircleShape, GlassStyle(tint = Color.White, sampleWallpaper = false, elevation = 0.5f, sheen = 0.4f))
        )
    }
}

/**
 * Large vertical slider (control center brightness / volume): the fill rises from the bottom,
 * the whole tile follows the finger directly.
 */
@Composable
fun GlassVerticalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onChangeFinished: () -> Unit = {},
) {
    val colors = Lumen.colors
    val v by rememberUpdatedState(value)
    var h by remember { mutableStateOf(1f) }
    Box(
        modifier
            .glass(SmoothRoundedShape(26.dp), GlassStyle(tint = colors.glassTint, sampleWallpaper = false, elevation = 0.6f))
            .clip(SmoothRoundedShape(26.dp))
            .pointerInput(Unit) {
                h = size.height.toFloat()
                detectVerticalDragGestures(
                    onDragEnd = onChangeFinished,
                    onVerticalDrag = { ch, dy ->
                        ch.consume()
                        onChange((v - dy / h).coerceIn(0f, 1f))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onChange((1f - pos.y / size.height).coerceIn(0f, 1f)); onChangeFinished()
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(value.coerceIn(0f, 1f))
                .background(Color.White.copy(alpha = 0.92f))
        )
        Icon(
            icon, null,
            tint = if (value > 0.18f) Color(0xFF3A3A3C) else colors.textPrimary,
            modifier = Modifier.padding(bottom = 16.dp).size(26.dp),
        )
    }
}

/** Horizontal slider (settings, media position). */
@Composable
fun GlassSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onChangeFinished: () -> Unit = {},
) {
    val colors = Lumen.colors
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val current by rememberUpdatedState(fraction)
    Box(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragEnd = onChangeFinished) { ch, dx ->
                    ch.consume()
                    val f = (current + dx / size.width).coerceIn(0f, 1f)
                    onChange(range.start + f * (range.endInclusive - range.start))
                }
            }
            .pointerInput(range) {
                detectTapGestures { pos ->
                    val f = (pos.x / size.width).coerceIn(0f, 1f)
                    onChange(range.start + f * (range.endInclusive - range.start)); onChangeFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.fillStrong))
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(colors.accent))
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.001f))
                .wrapThumb()
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 14.dp)
                    .size(28.dp)
                    .glass(CircleShape, GlassStyle(tint = Color.White, sampleWallpaper = false, elevation = 0.7f, sheen = 0.4f))
            )
        }
    }
}

private fun Modifier.wrapThumb() = this.height(28.dp)

/** Segmented control (theme, clock style…). */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Lumen.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.fill)
            .padding(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (sel) Modifier.glass(RoundedCornerShape(8.dp), GlassStyle(tint = if (colors.isDark) Color(0xFF636366) else Color.White, sampleWallpaper = false, elevation = 0.4f, sheen = 0.3f))
                        else Modifier
                    )
                    .pointerInput(i) { detectTapGestures { onSelect(i) } },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label, style = LumenType.footnote.copy(color = colors.textPrimary, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
            }
        }
    }
}

/** Search field with magnifier and clear button (glass capsule). */
@Composable
fun GlassSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
) {
    val colors = Lumen.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .glass(CircleShape, GlassStyle(tint = colors.glassTint, sampleWallpaper = false, elevation = 0.4f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) BasicText(placeholder, style = LumenType.body.copy(color = colors.textSecondary))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LumenType.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Cancel, "Löschen", tint = colors.textSecondary,
                modifier = Modifier.size(20.dp).pointerInput(Unit) { detectTapGestures { onChange("") } },
            )
        }
    }
}

// ============================================================================ grouped lists

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text.uppercase(),
        style = LumenType.footnote.copy(color = Lumen.colors.textSecondary),
        modifier = modifier.padding(start = 16.dp, bottom = 7.dp, top = 22.dp),
    )
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = LumenType.footnote.copy(color = Lumen.colors.textSecondary),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
    )
}

/** Inset grouped list section (iOS Settings style) on glass. */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(SmoothRoundedShape(20.dp), GlassStyle(tint = Lumen.colors.glassTintThick, sampleWallpaper = false, elevation = 0.3f, sheen = 0.5f))
            .clip(SmoothRoundedShape(20.dp)),
        content = content,
    )
}

/** Row inside a [GlassGroup]: optional colored icon tile, title, subtitle, trailing content. */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Lumen.colors.accent,
    subtitle: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
    chevron: Boolean = false,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = Lumen.colors
    var pressed by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(if (pressed) colors.fill else Color.Transparent)
            .then(
                if (onClick != null) Modifier.pointerInput(onClick) {
                    detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
                } else Modifier
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(30.dp).clip(SmoothRoundedShape(7.dp)).background(iconTint),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                BasicText(title, style = LumenType.body.copy(color = if (destructive) colors.destructive else colors.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) BasicText(subtitle, style = LumenType.footnote.copy(color = colors.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                BasicText(value, style = LumenType.body.copy(color = colors.textSecondary), maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (chevron) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (icon != null) 60.dp else 16.dp)
                    .height(0.6.dp)
                    .background(colors.separator)
            )
        }
    }
}

// ============================================================================ overlays

/**
 * Full-screen overlay frame: blurs the scene in proportion to [progress] and draws a scrim.
 * Content receives the same progress for its own entrance motion.
 */
@Composable
fun OverlayFrame(
    backdrop: Backdrop,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    blur: Dp = 44.dp,
    scrim: Color = Lumen.colors.scrim,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .backdropBlur(backdrop, blur, progress)
            .drawWithContent {
                drawRect(scrim.copy(alpha = scrim.alpha * progress().coerceIn(0f, 1f)))
                drawContent()
            },
        content = content,
    )
}

/** Grabber handle shown at the top of sheets. */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp, 5.dp).clip(CircleShape).background(Lumen.colors.textTertiary))
}
