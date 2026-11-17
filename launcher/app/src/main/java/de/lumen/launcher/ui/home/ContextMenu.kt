package de.lumen.launcher.ui.home

import android.content.pm.ShortcutInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize
import de.lumen.launcher.graph
import de.lumen.launcher.ui.FolderOpen
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.backdropBlur
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class MenuEntry(
    val title: String,
    val icon: ImageVector? = null,
    val bitmap: ImageBitmap? = null,
    val destructive: Boolean = false,
    val groupStart: Boolean = false,
    val action: () -> Unit,
)

/** Widget configuration request (opened from the context menu, handled by the widget editor). */
var pendingWidgetEdit by mutableStateOf<Long?>(null)

@Composable
fun ContextMenuOverlay(c: LauncherController, backdrop: Backdrop) {
    val state = c.menu
    var shown by remember { mutableStateOf<MenuState?>(null) }
    val progress = remember { Animatable(0f) }
    val motion = Lumen.motion
    LaunchedEffect(state) {
        if (state != null) {
            shown = state
            progress.snapTo(0f)
            progress.animateTo(1f, motion.spring(0.72f, 420f))
        } else if (shown != null) {
            progress.animateTo(0f, motion.spring(1f, 700f))
            shown = null
        }
    }
    val menu = shown ?: return
    // While a drag is starting the menu fades out immediately.
    val entries = rememberEntries(c, menu.target)

    Box(
        Modifier
            .fillMaxSize()
            .backdropBlur(backdrop, 22.dp) { progress.value }
            .background(Color.Black.copy(alpha = 0.12f * progress.value))
            .pointerInput(Unit) { detectTapGestures { c.menu = null } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val a = menu.anchor
            val menuW = 280.dp
            val menuWpx = with(density) { menuW.toPx() }
            val rowH = with(density) { 46.dp.toPx() }
            val estH = entries.size * rowH + with(density) { 12.dp.toPx() }
            val gap = with(density) { 12.dp.toPx() }
            val below = a.bottom + gap + estH < screenH - with(density) { 24.dp.toPx() }
            val y = if (below) a.bottom + gap else (a.top - gap - estH).coerceAtLeast(with(density) { 40.dp.toPx() })
            val alignLeft = a.center.x < screenW / 2
            val x = (if (alignLeft) a.left else a.right - menuWpx).coerceIn(with(density) { 12.dp.toPx() }, screenW - menuWpx - with(density) { 12.dp.toPx() })

            // Lifted preview of the pressed element.
            Preview(c, menu, Modifier.offset { IntOffset(a.left.roundToInt(), a.top.roundToInt()) }.graphicsLayer {
                val s = 1f + 0.06f * progress.value
                scaleX = s; scaleY = s
            })

            Column(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(menuW)
                    .graphicsLayer {
                        val p = progress.value
                        alpha = p.coerceIn(0f, 1f)
                        scaleX = 0.6f + 0.4f * p
                        scaleY = 0.6f + 0.4f * p
                        transformOrigin = TransformOrigin(if (alignLeft) 0.1f else 0.9f, if (below) 0f else 1f)
                    }
                    .glass(SmoothRoundedShape(18.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTintThick, elevation = 1.2f))
                    .padding(vertical = 6.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                entries.forEachIndexed { i, e ->
                    if (i > 0 && e.groupStart) {
                        Box(Modifier.fillMaxWidth().height(7.dp).background(Lumen.colors.fill))
                    } else if (i > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(Lumen.colors.separator))
                    }
                    MenuRow(e) { c.menu = null; e.action() }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(e: MenuEntry, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val colors = Lumen.colors
    val tint = if (e.destructive) colors.destructive else colors.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (pressed) colors.fill else Color.Transparent)
            .pointerInput(e) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(e.title, style = LumenType.body.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        when {
            e.bitmap != null -> Image(e.bitmap, null, Modifier.size(22.dp))
            e.icon != null -> Icon(e.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Preview(c: LauncherController, menu: MenuState, modifier: Modifier) {
    val density = LocalDensity.current
    val w: Dp = with(density) { menu.anchor.width.toDp() }
    val h: Dp = with(density) { menu.anchor.height.toDp() }
    when (val t = menu.target) {
        is MenuTarget.App -> IconImageView(rememberAppIcon(t.app), w, modifier, sampleWallpaper = t.folderId == null && !t.fromLibrary)
        is MenuTarget.Folder -> FolderIcon(t.item, w, modifier)
        is MenuTarget.Widget -> Box(modifier.size(w, h))
    }
}

@Composable
private fun rememberEntries(c: LauncherController, target: MenuTarget): List<MenuEntry> {
    val ctx = LocalContext.current
    val graph = ctx.graph
    val s = Lumen.settings
    return when (target) {
        is MenuTarget.App -> {
            val app = target.app
            val density = ctx.resources.displayMetrics.densityDpi
            val shortcuts by produceState<List<Pair<ShortcutInfo, ImageBitmap?>>>(emptyList(), app.key) {
                value = withContext(Dispatchers.IO) {
                    graph.apps.shortcuts(app).map { sc ->
                        sc to graph.apps.shortcutIcon(sc, density)?.let { d -> runCatching { d.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                    }
                }
            }
            buildList {
                shortcuts.forEach { (sc, bmp) ->
                    add(MenuEntry(sc.shortLabel?.toString() ?: sc.longLabel?.toString() ?: "", bitmap = bmp) {
                        graph.apps.startShortcut(sc, null)
                        c.recordLaunch(app.key)
                    })
                }
                val grp = shortcuts.isNotEmpty()
                if (target.item != null || target.folderId != null) {
                    add(MenuEntry("Home-Bildschirm bearbeiten", Icons.Rounded.Apps, groupStart = grp) { c.editMode = true })
                } else {
                    add(MenuEntry("Zum Home-Bildschirm", Icons.Rounded.AddToHomeScreen, groupStart = grp) { c.addToHome(app.key) })
                }
                add(MenuEntry("App-Info", Icons.Rounded.Info) { graph.apps.openAppInfo(app) })
                if (target.fromLibrary) {
                    add(MenuEntry("Kategorie ändern", Icons.Rounded.Category) { libraryCategoryRequest = app.key })
                    add(MenuEntry("App ausblenden", Icons.Rounded.VisibilityOff) {
                        graph.library.update { it.copy(hidden = it.hidden + app.key.value) }
                        c.showToast("„${app.label}“ ausgeblendet – in den Einstellungen wieder einblendbar")
                    })
                }
                when {
                    target.folderId != null -> add(MenuEntry("Aus Ordner entfernen", Icons.Rounded.RemoveCircleOutline) { c.removeFromFolder(target.folderId, app.key) })
                    target.item != null -> add(MenuEntry("Vom Home-Bildschirm entfernen", Icons.Rounded.RemoveCircleOutline) { c.removeFromHome(target.item.id) })
                }
                if (!app.isSystemApp) {
                    add(MenuEntry("App löschen", Icons.Rounded.Delete, destructive = true) { graph.apps.uninstall(app) })
                }
            }
        }
        is MenuTarget.Folder -> listOf(
            MenuEntry("Umbenennen", Icons.Rounded.DriveFileRenameOutline) {
                pendingFolderRename = target.item.id
                c.openFolder = FolderOpen(target.item.id, c.menu?.anchor ?: androidx.compose.ui.geometry.Rect.Zero)
            },
            MenuEntry("Home-Bildschirm bearbeiten", Icons.Rounded.Apps) { c.editMode = true },
            MenuEntry("Ordner entfernen", Icons.Rounded.FolderOff, destructive = true) {
                c.removeFromHome(target.item.id)
                c.showToast("Die Apps bleiben in der App-Bibliothek")
            },
        )
        is MenuTarget.Widget -> buildList {
            val item = target.item
            if (item is WidgetItem) {
                WidgetSize.entries.filter { it != item.size && it.w <= s.gridColumns && it.h <= s.gridRows }.forEach { size ->
                    add(MenuEntry("Größe: ${size.title}", Icons.Rounded.Tune) {
                        c.updateLayout { LayoutOps.resizeWidget(it, item.id, size, s.gridColumns, s.gridRows) }
                    })
                }
                if (item.kind in configurableWidgets) {
                    add(MenuEntry("Widget bearbeiten", Icons.Rounded.Edit) { pendingWidgetEdit = item.id })
                }
            }
            add(MenuEntry("Home-Bildschirm bearbeiten", Icons.Rounded.Apps, groupStart = true) { c.editMode = true })
            add(MenuEntry("Widget entfernen", Icons.Rounded.RemoveCircleOutline, destructive = true) { c.removeFromHome(item.id) })
        }
    }
}

/** Widgets that have their own options sheet. */
val configurableWidgets = setOf(WidgetKind.CLOCK, WidgetKind.WEATHER, WidgetKind.SHORTCUTS)

/** Category change request from the library context menu. */
var libraryCategoryRequest by mutableStateOf<de.lumen.launcher.data.apps.AppKey?>(null)
