package de.lumen.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.ui.widgets.BuiltinWidget
import kotlin.math.roundToInt

/** The item under the finger while dragging: lifted (scaled up) and following the pointer. */
@Composable
fun DragLayer(c: LauncherController) {
    val p = c.drag.active ?: return
    val apps = LocalApps.current
    val density = LocalDensity.current
    val lift by animateFloatAsState(if (c.drag.settling) 1f else if (c.drag.folderHover != null) 0.9f else 1.1f, Lumen.motion.bouncy(), label = "lift")
    val w = with(density) { p.bounds.width.toDp() }
    val h = with(density) { p.bounds.height.toDp() }
    Box(
        Modifier
            .offset {
                val tl = if (c.drag.settling) c.drag.settle.value else c.drag.pointer - c.drag.grab
                IntOffset(tl.x.roundToInt(), tl.y.roundToInt())
            }
            .graphicsLayer { scaleX = lift; scaleY = lift; alpha = 0.97f }
            .size(w, h),
    ) {
        when (val v = p.visual) {
            is DragVisual.App -> IconImageView(rememberAppIcon(apps[v.app]), w, sampleWallpaper = false)
            is DragVisual.Folder -> FolderIcon(v.item, w, sampleWallpaper = false)
            is DragVisual.Widget -> Box(
                Modifier
                    .fillMaxSize()
                    .glass(SmoothRoundedShape(HomeDimens.widgetCorner), GlassStyle(elevation = 1.4f)),
            ) {
                when (val item = v.item) {
                    is WidgetItem -> BuiltinWidget(c, item)
                    is NativeWidgetItem -> BasicText(
                        item.provider.substringAfterLast('.'),
                        style = LumenType.headline.copy(color = Color.White),
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> Unit
                }
            }
        }
    }
}
