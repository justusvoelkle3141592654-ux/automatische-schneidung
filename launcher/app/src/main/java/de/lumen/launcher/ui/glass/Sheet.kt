package de.lumen.launcher.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.launch

/**
 * Glass bottom sheet with spring entrance, drag-to-dismiss and blurred backdrop.
 * The sheet stays composed while its exit animation runs.
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    title: String? = null,
    maxHeightFraction: Float = 0.88f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var composed by remember { mutableStateOf(false) }
    val motion = Lumen.motion
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) {
        if (visible) {
            composed = true
            progress.animateTo(1f, motion.spring(0.86f, 320f))
        } else if (composed) {
            progress.animateTo(0f, motion.spring(1f, 420f))
            composed = false
        }
    }
    if (!composed) return
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    OverlayFrame(backdrop, { progress.value }, blur = 26.dp, scrim = Color.Black.copy(alpha = 0.28f)) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = statusTop + 12.dp), contentAlignment = Alignment.BottomCenter) {
            val h = constraints.maxHeight.toFloat()
            var dragOffset by remember { mutableStateOf(0f) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * maxHeightFraction)
                    .graphicsLayer { translationY = (1f - progress.value) * h + dragOffset }
                    .padding(horizontal = 8.dp)
                    .glass(SmoothRoundedShape(34.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTintThick, elevation = 1.4f))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .imePadding()
                    .padding(bottom = navBottom + 8.dp),
            ) {
                // Header with grabber; dragging it down dismisses the sheet.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > 120.dp.toPx()) onDismiss()
                                    scope.launch {
                                        val a = Animatable(dragOffset)
                                        a.animateTo(0f) { dragOffset = value }
                                    }
                                },
                                onVerticalDrag = { ch, dy -> ch.consume(); dragOffset = (dragOffset + dy).coerceAtLeast(0f) },
                            )
                        }
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SheetGrabber()
                    if (title != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(title, style = LumenType.title3.copy(color = Lumen.colors.textPrimary), modifier = Modifier.weight(1f))
                            GlassIconButton(Icons.Rounded.Close, "Schließen", size = 32.dp) { onDismiss() }
                        }
                    }
                }
                content()
            }
        }
    }
}
