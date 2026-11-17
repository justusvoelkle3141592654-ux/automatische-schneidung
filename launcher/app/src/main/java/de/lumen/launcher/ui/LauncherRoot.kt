package de.lumen.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.lumen.launcher.data.apps.IconSpec
import de.lumen.launcher.data.settings.ThemeMode
import de.lumen.launcher.ui.glass.BackdropFallback
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.backdropSource
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.glass.rememberBackdrop
import de.lumen.launcher.ui.home.ContextMenuOverlay
import de.lumen.launcher.ui.home.DragLayer
import de.lumen.launcher.ui.home.FolderOverlay
import de.lumen.launcher.ui.home.HomeScreen
import de.lumen.launcher.ui.home.LocalApps
import de.lumen.launcher.ui.home.LocalController
import de.lumen.launcher.ui.home.LocalIconSpec
import de.lumen.launcher.ui.home.homeGestures
import de.lumen.launcher.ui.theme.AccentPalette
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenTheme
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.ui.wallpaper.LocalWallpaper
import de.lumen.launcher.ui.wallpaper.WallpaperState
import de.lumen.launcher.ui.wallpaper.rememberWallpaperState
import androidx.compose.ui.graphics.toArgb

@Composable
fun LauncherRoot(c: LauncherController, onStatusBarLight: (Boolean) -> Unit) {
    val settings by c.graph.settings.state.collectAsState()
    val layout by c.graph.layout.state.collectAsState()
    val apps by c.graph.apps.apps.collectAsState()
    val appMap = remember(apps) { apps.associateBy { it.key } }
    var screen by remember { mutableStateOf(IntSize.Zero) }

    LumenTheme(settings) {
        val dark = when (settings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val wallpaper = rememberWallpaperState(settings, screen, dark)
        SideEffect { BackdropFallback.current = wallpaper }
        val density = LocalDensity.current
        val iconPx = with(density) { settings.iconSizeDp.dp.roundToPx() }
        val accent = AccentPalette.getOrElse(settings.accentIndex) { AccentPalette[0] }.second
        val spec = remember(iconPx, settings.iconStyle, settings.iconPackPackage, settings.maskIconPackIcons, accent) {
            IconSpec(iconPx, settings.iconStyle, settings.iconPackPackage, settings.maskIconPackIcons, accent.toArgb())
        }
        val backdrop = rememberBackdrop()
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        CompositionLocalProvider(
            LocalWallpaper provides wallpaper,
            LocalApps provides appMap,
            LocalIconSpec provides spec,
            LocalController provides c,
        ) {
            // Status bar icons: dark on light wallpapers when nothing covers the home screen.
            val lightBar = wallpaper.isLight && !c.anyOverlay && c.openFolder == null
            SideEffect { onStatusBarLight(lightBar) }

            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { screen = it }
                    .dragTracking(c),
            ) {
                Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                    WallpaperLayer(wallpaper)
                    HomeScreen(
                        c, layout,
                        Modifier
                            .graphicsLayer {
                                // Home recedes while overlays cover it, and zooms when apps open/close.
                                val depth = c.overlays.values.maxOf { it.progress.value }.coerceIn(0f, 1f)
                                val folder = if (c.openFolder != null) 1f else 0f
                                val s = c.homeZoom.value * (1f - 0.06f * maxOf(depth, folder * 0.6f))
                                scaleX = s
                                scaleY = s
                                alpha = 1f - 0.25f * depth
                            }
                            .homeGestures(c) { with(density) { (statusTop + 110.dp).toPx() } },
                    )
                }
                FolderOverlay(c, backdrop)
                OverlayHost(c, backdrop)
                ContextMenuOverlay(c, backdrop)
                DragLayer(c)
                Toast(c, statusTop)
            }
        }
    }
}

/** Draws the launcher wallpaper (or nothing, letting the system wallpaper show through). */
@Composable
private fun WallpaperLayer(wp: WallpaperState) {
    val img = wp.image ?: return
    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

/**
 * Root-level pointer tracking for drag & drop. It observes events before any child (Initial pass)
 * so a drag can start from any tile and survive page changes or the tile leaving composition.
 */
private fun Modifier.dragTracking(c: LauncherController): Modifier = pointerInput(c) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (c.drag.active != null) {
                if (ch.pressed) {
                    c.moveDrag(ch.position)
                    ch.consume()
                } else {
                    c.endDrag()
                    ch.consume()
                    break
                }
            } else {
                val p = c.drag.pending
                if (p != null && ch.pressed && (ch.position - p.down).getDistance() > slop) {
                    c.startDrag(ch.position)
                    c.moveDrag(ch.position)
                    ch.consume()
                }
                if (!ch.pressed) {
                    c.drag.pending = null
                    break
                }
            }
        }
    }
}

@Composable
private fun Toast(c: LauncherController, statusTop: androidx.compose.ui.unit.Dp) {
    val msg = c.toast
    var last by remember { mutableStateOf("") }
    if (msg != null) last = msg
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(Lumen.motion.fade()) + slideInVertically(Lumen.motion.bouncy()) { -it },
            exit = fadeOut(Lumen.motion.fade()) + slideOutVertically { -it },
        ) {
            BasicText(
                last,
                style = LumenType.subhead.copy(color = Lumen.colors.textPrimary),
                modifier = Modifier
                    .padding(top = statusTop + 8.dp, start = 24.dp, end = 24.dp)
                    .glass(RoundedCornerShape(22.dp), GlassStyle(sampleWallpaper = true, tint = Lumen.colors.glassTintThick, elevation = 1f))
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

/** Overlays added in later phases (library, search, control center, notifications, overview, settings). */
@Composable
fun OverlayHost(c: LauncherController, backdrop: de.lumen.launcher.ui.glass.Backdrop) {
    de.lumen.launcher.ui.library.LibraryOverlay(c, backdrop)
    de.lumen.launcher.ui.search.SearchOverlay(c, backdrop)
    de.lumen.launcher.ui.control.ControlCenter(c, backdrop)
    de.lumen.launcher.ui.notifications.NotificationCenter(c, backdrop)
    de.lumen.launcher.ui.library.CategoryPickerSheet(c, backdrop)
    de.lumen.launcher.ui.widgets.WidgetPickerSheet(c, backdrop)
    de.lumen.launcher.ui.widgets.WidgetEditorSheet(c, backdrop)
    de.lumen.launcher.ui.widgets.RemindersSheet(c, backdrop)
    de.lumen.launcher.ui.overview.OverviewOverlay(c, backdrop)
    de.lumen.launcher.ui.settings.PageManagerSheet(c, backdrop)
    de.lumen.launcher.ui.settings.SettingsOverlay(c, backdrop)
    de.lumen.launcher.ui.settings.OnboardingSheet(c, backdrop)
}
