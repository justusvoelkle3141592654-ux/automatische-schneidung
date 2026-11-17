package de.lumen.launcher.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.UsageRanking
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.system.RelativeTime
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.Overlay
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.OverlayFrame
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.glass.liquidPress
import de.lumen.launcher.ui.home.IconImageView
import de.lumen.launcher.ui.home.LocalApps
import de.lumen.launcher.ui.home.rememberAppIcon
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Launcher-internal overview (swipe up and hold). Android does not let third-party launchers show
 * the system's recent tasks, so this shows the apps recently opened from Lumen plus the pages.
 */
@Composable
fun OverviewOverlay(c: LauncherController, backdrop: Backdrop) {
    val anim = c.anim(Overlay.OVERVIEW)
    if (!anim.visible) return
    val apps = LocalApps.current
    val lib by c.graph.library.state.collectAsState()
    val layout by c.graph.layout.state.collectAsState()
    val recents = remember(lib, apps) { UsageRanking.recents(lib.usage, 12).mapNotNull { apps[AppKey(it)] } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now = System.currentTimeMillis()
    val scope = rememberCoroutineScope()

    OverlayFrame(backdrop, { anim.progress.value }, blur = 40.dp, scrim = Color.Black.copy(alpha = 0.35f)) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { c.close(Overlay.OVERVIEW) } })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardW = (maxWidth * 0.62f)
            val sidePad = (maxWidth - cardW) / 2
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = anim.progress.value.coerceIn(0f, 1f)
                        val s = 1.08f - 0.08f * p
                        scaleX = s; scaleY = s; alpha = p
                    }
                    .padding(top = statusTop + 24.dp, bottom = navBottom + 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicText("Zuletzt in Lumen geöffnet", style = LumenType.headline.copy(color = Color.White))
                }
                Spacer(Modifier.height(18.dp))
                if (recents.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        BasicText("Noch keine Apps geöffnet", style = LumenType.body.copy(color = Color.White.copy(alpha = 0.7f)))
                    }
                } else {
                    LazyRow(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = sidePad),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(recents, key = { _, a -> a.key.value }) { _, a ->
                            Column(
                                Modifier
                                    .width(cardW)
                                    .fillMaxHeight(0.82f)
                                    .glass(SmoothRoundedShape(34.dp), GlassStyle(sampleWallpaper = false, tint = Color.White.copy(alpha = 0.10f), elevation = 1.2f))
                                    .clip(SmoothRoundedShape(34.dp))
                                    .liquidPress(pressScale = 0.97f, shape = SmoothRoundedShape(34.dp)) { c.close(Overlay.OVERVIEW); c.launch(a, null) }
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                IconImageView(rememberAppIcon(a), 96.dp, sampleWallpaper = false)
                                Spacer(Modifier.height(18.dp))
                                BasicText(a.label, style = LumenType.title2.copy(color = Color.White, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val used = lib.usage[a.key.value]
                                if (used != null) {
                                    Spacer(Modifier.height(4.dp))
                                    BasicText("${RelativeTime.format(used.lastLaunch, now)} · ${used.launches}× geöffnet", style = LumenType.footnote.copy(color = Color.White.copy(alpha = 0.7f)))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                // Page overview: tap a page to jump there.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    layout.pages.forEachIndexed { i, page ->
                        Box(
                            Modifier
                                .size(46.dp, 80.dp)
                                .glass(SmoothRoundedShape(10.dp), GlassStyle(sampleWallpaper = false, tint = if (c.pager?.currentPage == i) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.10f), elevation = 0.4f))
                                .clip(SmoothRoundedShape(10.dp))
                                .liquidPress(shape = SmoothRoundedShape(10.dp)) {
                                    c.close(Overlay.OVERVIEW)
                                    scope.launch { c.pager?.animateScrollToPage(i) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val cols = c.graph.settings.value.gridColumns
                            val rows = c.graph.settings.value.gridRows
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                                val cw = size.width / cols
                                val ch = size.height / rows
                                page.items.forEach { item ->
                                    val r = item.cell
                                    val pad = 1.2.dp.toPx()
                                    drawRoundRect(
                                        Color.White.copy(alpha = if (item is AppItem || item is FolderItem) 0.85f else 0.45f),
                                        topLeft = androidx.compose.ui.geometry.Offset(r.x * cw + pad, r.y * ch + pad),
                                        size = androidx.compose.ui.geometry.Size(r.w * cw - pad * 2, r.h * ch - pad * 2),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassCapsuleButton("Apps", icon = Icons.Rounded.Apps) { c.open(Overlay.LIBRARY) }
                    GlassCapsuleButton("Anordnen", icon = Icons.Rounded.Edit) { c.close(Overlay.OVERVIEW); c.editMode = true }
                    GlassCapsuleButton("Lumen", icon = Icons.Rounded.Settings) { c.close(Overlay.OVERVIEW); c.settingsOpen = true }
                }
            }
        }
    }
}
