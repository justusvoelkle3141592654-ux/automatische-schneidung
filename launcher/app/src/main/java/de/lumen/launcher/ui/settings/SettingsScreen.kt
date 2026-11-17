package de.lumen.launcher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.settings.GestureAction
import de.lumen.launcher.data.settings.IconStyle
import de.lumen.launcher.data.settings.LauncherSettings
import de.lumen.launcher.data.settings.ThemeMode
import de.lumen.launcher.data.settings.WallpaperMode
import de.lumen.launcher.graph
import de.lumen.launcher.system.LauncherSystem
import de.lumen.launcher.system.LumenLockService
import de.lumen.launcher.system.NotificationHub
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSegmented
import de.lumen.launcher.ui.glass.GlassSlider
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.GlassSwitch
import de.lumen.launcher.ui.glass.OverlayFrame
import de.lumen.launcher.ui.glass.SectionFooter
import de.lumen.launcher.ui.glass.SectionHeader
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.glass.liquidPress
import de.lumen.launcher.ui.home.IconImageView
import de.lumen.launcher.ui.home.LocalApps
import de.lumen.launcher.ui.home.rememberAppIcon
import de.lumen.launcher.ui.theme.AccentPalette
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.ui.wallpaper.WallpaperPresets
import de.lumen.launcher.ui.wallpaper.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class SettingsPage(val title: String) {
    ROOT("Einstellungen"), WALLPAPER("Hintergrundbild"), ICONS("App-Symbole"), DOCK("Dock"),
    GLASS("Glas & Bewegung"), HOME("Home-Bildschirm"), GESTURES("Gesten"), PERMISSIONS("Berechtigungen"),
    LIBRARY("App-Bibliothek"), ABOUT("Über Lumen"),
}

@Composable
fun SettingsOverlay(c: LauncherController, backdrop: Backdrop) {
    val progress = remember { Animatable(0f) }
    var composed by remember { mutableStateOf(false) }
    val motion = Lumen.motion
    LaunchedEffect(c.settingsOpen) {
        if (c.settingsOpen) { composed = true; progress.animateTo(1f, motion.spring(0.9f, 300f)) }
        else if (composed) { progress.animateTo(0f, motion.spring(1f, 400f)); composed = false }
    }
    if (!composed) return
    var stack by remember { mutableStateOf(listOf(SettingsPage.ROOT)) }
    LaunchedEffect(c.settingsOpen) { if (c.settingsOpen) stack = listOf(SettingsPage.ROOT) }
    BackHandler(enabled = c.settingsOpen) {
        if (stack.size > 1) stack = stack.dropLast(1) else c.settingsOpen = false
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val colors = Lumen.colors
    OverlayFrame(backdrop, { progress.value }, blur = 64.dp, scrim = if (colors.isDark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.45f)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    translationY = (1f - p) * 220f
                    alpha = p
                }
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = statusTop),
        ) {
            // Navigation bar
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    Row(
                        Modifier.clip(CircleShape).liquidPress { stack = stack.dropLast(1) }.padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, null, tint = colors.accent, modifier = Modifier.size(30.dp))
                        BasicText(stack[stack.size - 2].title, style = LumenType.body.copy(color = colors.accent))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).glass(CircleShape, GlassStyle(sampleWallpaper = false, elevation = 0.3f)).clip(CircleShape).liquidPress(shape = CircleShape) { c.settingsOpen = false },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Schließen", tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
            }
            AnimatedContent(
                targetState = stack,
                transitionSpec = {
                    val forward = targetState.size >= initialState.size
                    (slideInHorizontally(motion.spring(0.92f, 380f)) { if (forward) it else -it / 3 } + fadeIn(motion.fade(200))) togetherWith
                        (slideOutHorizontally(motion.spring(0.92f, 380f)) { if (forward) -it / 3 else it } + fadeOut(motion.fade(160)))
                },
                label = "settings",
            ) { st ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = navBottom + 32.dp),
                ) {
                    val p = st.last()
                    BasicText(p.title, style = LumenType.largeTitle.copy(color = colors.textPrimary), modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
                    when (p) {
                        SettingsPage.ROOT -> RootPage(c) { stack = stack + it }
                        SettingsPage.WALLPAPER -> WallpaperPage(c)
                        SettingsPage.ICONS -> IconsPage(c)
                        SettingsPage.DOCK -> DockPage(c)
                        SettingsPage.GLASS -> GlassPage(c)
                        SettingsPage.HOME -> HomePage(c)
                        SettingsPage.GESTURES -> GesturesPage(c)
                        SettingsPage.PERMISSIONS -> PermissionsPage(c)
                        SettingsPage.LIBRARY -> LibraryPage(c)
                        SettingsPage.ABOUT -> AboutPage(c)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun settings(): LauncherSettings = Lumen.settings

private fun LauncherController.set(f: (LauncherSettings) -> LauncherSettings) = graph.settings.update(f)

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, showDivider: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            BasicText(title, style = LumenType.body.copy(color = Lumen.colors.textPrimary), modifier = Modifier.weight(1f))
            BasicText(valueText, style = LumenType.body.copy(color = Lumen.colors.textSecondary))
        }
        Spacer(Modifier.height(6.dp))
        GlassSlider(value, onChange, range = range)
    }
    if (showDivider) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(Lumen.colors.separator))
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, icon: ImageVector? = null, tint: Color = Lumen.colors.accent, showDivider: Boolean = true, onChange: (Boolean) -> Unit) {
    GlassRow(title, icon = icon, iconTint = tint, subtitle = subtitle, showDivider = showDivider, trailing = { GlassSwitch(checked, onChange) })
}

// ------------------------------------------------------------------ pages

@Composable
private fun RootPage(c: LauncherController, open: (SettingsPage) -> Unit) {
    val ctx = LocalContext.current
    val s = settings()
    var isDefault by remember { mutableStateOf(LauncherSystem.isDefaultHome(ctx)) }
    LaunchedEffect(c.settingsOpen) { isDefault = LauncherSystem.isDefaultHome(ctx) }
    if (!isDefault) {
        Spacer(Modifier.height(12.dp))
        GlassGroup {
            GlassRow(
                "Lumen als Standard festlegen",
                icon = Icons.Rounded.Home, iconTint = Color(0xFF0A84FF),
                subtitle = "Damit die Home-Taste immer zu Lumen führt und App-Shortcuts verfügbar sind.",
                showDivider = false, chevron = true,
                onClick = { c.bridge?.startForResult(LauncherSystem.defaultHomeIntent(ctx)) { _, _ -> isDefault = LauncherSystem.isDefaultHome(ctx) } },
            )
        }
    }
    SectionHeader("Darstellung")
    GlassGroup {
        Column(Modifier.padding(16.dp)) {
            GlassSegmented(listOf("Automatisch", "Hell", "Dunkel"), s.themeMode.ordinal, { i -> c.set { it.copy(themeMode = ThemeMode.entries[i]) } })
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(Lumen.colors.separator))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicText("Akzentfarbe", style = LumenType.body.copy(color = Lumen.colors.textPrimary))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AccentPalette.forEachIndexed { i, (name, color) ->
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(color)
                            .then(if (i == s.accentIndex) Modifier.border(3.dp, Lumen.colors.textPrimary.copy(alpha = 0.85f), CircleShape) else Modifier)
                            .pointerInput(i) { detectTapGestures { c.set { it.copy(accentIndex = i) } } },
                        contentAlignment = Alignment.Center,
                    ) { if (i == s.accentIndex) Icon(Icons.Rounded.Check, name, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(Lumen.colors.separator))
        GlassRow("Hintergrundbild", icon = Icons.Rounded.Wallpaper, iconTint = Color(0xFF5AC8FA), chevron = true, onClick = { open(SettingsPage.WALLPAPER) })
        GlassRow("App-Symbole", icon = Icons.Rounded.Apps, iconTint = Color(0xFF5E5CE6), chevron = true, onClick = { open(SettingsPage.ICONS) })
        GlassRow("Dock", icon = Icons.Rounded.Dock, iconTint = Color(0xFF34C759), chevron = true, onClick = { open(SettingsPage.DOCK) })
        GlassRow("Glas & Bewegung", icon = Icons.Rounded.BlurOn, iconTint = Color(0xFFFF9F0A), showDivider = false, chevron = true, onClick = { open(SettingsPage.GLASS) })
    }
    SectionHeader("Bedienung")
    GlassGroup {
        GlassRow("Home-Bildschirm", icon = Icons.Rounded.GridView, iconTint = Color(0xFF0A84FF), chevron = true, onClick = { open(SettingsPage.HOME) })
        GlassRow("Gesten", icon = Icons.Rounded.Swipe, iconTint = Color(0xFFFF375F), chevron = true, onClick = { open(SettingsPage.GESTURES) })
        GlassRow("App-Bibliothek", icon = Icons.Rounded.Category, iconTint = Color(0xFFBF5AF2), chevron = true, onClick = { open(SettingsPage.LIBRARY) })
        GlassRow("Berechtigungen", icon = Icons.Rounded.SettingsSuggest, iconTint = Color(0xFF8E8E93), showDivider = false, chevron = true, onClick = { open(SettingsPage.PERMISSIONS) })
    }
    SectionHeader("Info")
    GlassGroup {
        GlassRow("Über Lumen", icon = Icons.Rounded.Info, iconTint = Color(0xFF8E8E93), showDivider = false, chevron = true, onClick = { open(SettingsPage.ABOUT) })
    }
}

@Composable
private fun WallpaperPage(c: LauncherController) {
    val ctx = LocalContext.current
    val s = settings()
    val scope = rememberCoroutineScope()
    val thumbs by produceState<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList()) {
        value = withContext(Dispatchers.Default) { WallpaperPresets.map { WallpaperRenderer.renderPreset(it, 180, 390, 0f).asImageBitmap() } }
    }
    SectionHeader("Lumen-Hintergründe")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperPresets.take(3).forEachIndexed { i, p -> PresetThumb(c, i, thumbs.getOrNull(i), p.name, s, Modifier.weight(1f)) }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperPresets.drop(3).forEachIndexed { j, p -> PresetThumb(c, j + 3, thumbs.getOrNull(j + 3), p.name, s, Modifier.weight(1f)) }
    }
    SectionHeader("Eigenes Bild")
    GlassGroup {
        GlassRow("Foto auswählen", icon = Icons.Rounded.Wallpaper, iconTint = Color(0xFF34C759), subtitle = "Systemeigene Fotoauswahl – kein Zugriff auf die gesamte Galerie nötig", chevron = true, onClick = {
            c.bridge?.pickImage { uri ->
                if (uri != null) scope.launch {
                    val f = withContext(Dispatchers.IO) { LauncherSystem.importWallpaper(ctx, uri) }
                    if (f != null) c.set { it.copy(wallpaperMode = WallpaperMode.CUSTOM, customWallpaperFile = f.path) }
                    else c.showToast("Bild konnte nicht geladen werden")
                }
            }
        })
        SwitchRow("System-Hintergrund verwenden", s.wallpaperMode == WallpaperMode.SYSTEM, subtitle = "Zeigt das Android-Hintergrundbild. Glasflächen werden dann ohne Unschärfe-Probe getönt dargestellt.", showDivider = false) { on ->
            c.set { it.copy(wallpaperMode = if (on) WallpaperMode.SYSTEM else if (it.customWallpaperFile != null) WallpaperMode.CUSTOM else WallpaperMode.PRESET) }
        }
    }
    SectionHeader("Anpassen")
    GlassGroup {
        SliderRow("Abdunkeln", s.wallpaperDim, 0f..0.6f, "${(s.wallpaperDim * 100).roundToInt()} %") { v -> c.set { it.copy(wallpaperDim = v) } }
        GlassRow("Auch als System-Hintergrund festlegen", subtitle = "Übernimmt das aktuelle Bild für Sperrbildschirm und Übersicht", showDivider = false, chevron = true, onClick = {
            scope.launch {
                val ok = withContext(Dispatchers.Default) {
                    val st = c.graph.settings.value
                    val dm = ctx.resources.displayMetrics
                    val bmp = when (st.wallpaperMode) {
                        WallpaperMode.CUSTOM -> st.customWallpaperFile?.let { WallpaperRenderer.loadCustom(File(it), dm.widthPixels, dm.heightPixels, 0f) }
                        WallpaperMode.PRESET -> WallpaperRenderer.renderPreset(WallpaperPresets[st.wallpaperPreset.coerceIn(0, WallpaperPresets.lastIndex)], dm.widthPixels, dm.heightPixels, 0f)
                        WallpaperMode.SYSTEM -> null
                    }
                    bmp?.let { LauncherSystem.applySystemWallpaper(ctx, it) } ?: false
                }
                c.showToast(if (ok) "System-Hintergrund aktualisiert" else "Nicht möglich (System-Hintergrund ist aktiv oder Fehler)")
            }
        })
    }
}

@Composable
private fun PresetThumb(c: LauncherController, index: Int, img: androidx.compose.ui.graphics.ImageBitmap?, name: String, s: LauncherSettings, modifier: Modifier) {
    val selected = s.wallpaperMode == WallpaperMode.PRESET && s.wallpaperPreset == index
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.46f).clip(SmoothRoundedShape(16.dp))
                .then(if (selected) Modifier.border(3.dp, Lumen.colors.accent, SmoothRoundedShape(16.dp)) else Modifier)
                .background(Lumen.colors.fill)
                .liquidPress(shape = SmoothRoundedShape(16.dp)) { c.set { it.copy(wallpaperMode = WallpaperMode.PRESET, wallpaperPreset = index) } },
        ) { img?.let { Image(it, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
        Spacer(Modifier.height(4.dp))
        BasicText(name, style = LumenType.footnote.copy(color = Lumen.colors.textSecondary))
    }
}

@Composable
private fun IconsPage(c: LauncherController) {
    val ctx = LocalContext.current
    val s = settings()
    val packs by produceState(emptyList<de.lumen.launcher.data.apps.IconPackInfo>()) { value = withContext(Dispatchers.IO) { ctx.graph.iconPacks.installedPacks() } }
    SectionHeader("Stil")
    GlassGroup {
        Column(Modifier.padding(16.dp)) {
            GlassSegmented(listOf("Standard", "Dunkel", "Getönt", "Klar"), s.iconStyle.ordinal, { i -> c.set { it.copy(iconStyle = IconStyle.entries[i]) } })
            Spacer(Modifier.height(14.dp))
            val sample = LocalApps.current.values.take(4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                sample.forEach { IconImageView(rememberAppIcon(it), s.iconSizeDp.dp.coerceAtMost(64.dp), sampleWallpaper = false) }
            }
        }
    }
    SectionFooter("„Getönt“ verwendet die Akzentfarbe und – wo vorhanden – die einfarbige Symbolvariante der App.")
    SectionHeader("Größe & Abstände")
    GlassGroup {
        SliderRow("Symbolgröße", s.iconSizeDp, 44f..76f, "${s.iconSizeDp.roundToInt()} dp") { v -> c.set { it.copy(iconSizeDp = v) } }
        SliderRow("Seitenabstand", s.iconSpacing, 0.6f..1.4f, "${(s.iconSpacing * 100).roundToInt()} %") { v -> c.set { it.copy(iconSpacing = v) } }
        SliderRow("Schriftgröße", s.labelScale, 0.8f..1.3f, "${(s.labelScale * 100).roundToInt()} %") { v -> c.set { it.copy(labelScale = v) } }
        SwitchRow("Namen anzeigen", s.showLabels, showDivider = false) { v -> c.set { it.copy(showLabels = v) } }
    }
    SectionHeader("Symbolpaket")
    GlassGroup {
        GlassRow("Keins (App-eigene Symbole)", trailing = { if (s.iconPackPackage == null) Icon(Icons.Rounded.Check, null, tint = Lumen.colors.accent) }, onClick = { c.set { it.copy(iconPackPackage = null) }; ctx.graph.icons.clear() })
        packs.forEach { p ->
            GlassRow(p.label, trailing = { if (s.iconPackPackage == p.packageName) Icon(Icons.Rounded.Check, null, tint = Lumen.colors.accent) }, onClick = {
                c.set { it.copy(iconPackPackage = p.packageName) }; ctx.graph.icons.clear()
            })
        }
        SwitchRow("Paket-Symbole in Lumen-Form", s.maskIconPackIcons, showDivider = false) { v -> c.set { it.copy(maskIconPackIcons = v) } }
    }
    if (packs.isEmpty()) SectionFooter("Kein Symbolpaket installiert. Unterstützt werden Pakete im verbreiteten ADW/Nova-Format aus dem App-Store.")
}

@Composable
private fun DockPage(c: LauncherController) {
    val s = settings()
    SectionHeader("Dock")
    GlassGroup {
        SliderRow("Größe", s.dockScale, 0.8f..1.15f, "${(s.dockScale * 100).roundToInt()} %") { v -> c.set { it.copy(dockScale = v) } }
        SliderRow("Deckkraft", s.dockOpacity, 0f..1f, "${(s.dockOpacity * 100).roundToInt()} %") { v -> c.set { it.copy(dockOpacity = v) } }
        Column(Modifier.padding(16.dp)) {
            BasicText("Anzahl Apps", style = LumenType.body.copy(color = Lumen.colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            GlassSegmented(listOf("3", "4", "5", "6"), (s.dockMaxApps - 3).coerceIn(0, 3), { i -> c.set { it.copy(dockMaxApps = i + 3) } })
        }
    }
    SectionFooter("Apps einfach ins Dock ziehen oder herausziehen. Bereits vorhandene Apps bleiben beim Verringern erhalten.")
}

@Composable
private fun GlassPage(c: LauncherController) {
    val s = settings()
    SectionHeader("Glas")
    GlassGroup {
        SliderRow("Unschärfe", s.blurStrength, 0f..1f, "${(s.blurStrength * 100).roundToInt()} %") { v -> c.set { it.copy(blurStrength = v) } }
        SwitchRow("Lichtbrechung an Kanten", s.liquidRefraction, subtitle = if (android.os.Build.VERSION.SDK_INT >= 33) "Linseneffekt im Dock" else "Erfordert Android 13 oder neuer") { v -> c.set { it.copy(liquidRefraction = v) } }
        SwitchRow("Transparenz reduzieren", s.reduceTransparency, subtitle = "Ersetzt Glas durch deckende Flächen", showDivider = false) { v -> c.set { it.copy(reduceTransparency = v) } }
    }
    SectionHeader("Bewegung")
    GlassGroup {
        SwitchRow("Animationen", s.animationsEnabled, icon = Icons.Rounded.Animation, tint = Color(0xFFFF9F0A)) { v -> c.set { it.copy(animationsEnabled = v) } }
        SliderRow("Geschwindigkeit", s.animationSpeed, 0.5f..2f, "${"%.1f".format(s.animationSpeed)}×", showDivider = false) { v -> c.set { it.copy(animationSpeed = v) } }
    }
}

@Composable
private fun HomePage(c: LauncherController) {
    val s = settings()
    val layout by c.graph.layout.state.collectAsState()
    fun grid(cols: Int, rows: Int) {
        c.set { it.copy(gridColumns = cols, gridRows = rows) }
        c.updateLayout { LayoutOps.applyGrid(it, cols, rows) }
    }
    SectionHeader("Raster")
    GlassGroup {
        Column(Modifier.padding(16.dp)) {
            BasicText("Spalten", style = LumenType.body.copy(color = Lumen.colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            GlassSegmented(listOf("3", "4", "5", "6"), (s.gridColumns - 3).coerceIn(0, 3), { i -> grid(i + 3, s.gridRows) })
            Spacer(Modifier.height(14.dp))
            BasicText("Zeilen", style = LumenType.body.copy(color = Lumen.colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            GlassSegmented(listOf("4", "5", "6", "7", "8"), (s.gridRows - 4).coerceIn(0, 4), { i -> grid(s.gridColumns, i + 4) })
        }
    }
    SectionFooter("Passt beim Verkleinern nicht mehr alles auf eine Seite, werden Elemente auf neue Seiten verschoben.")
    SectionHeader("Seiten")
    GlassGroup {
        GlassRow("Seiten verwalten", value = "${layout.pages.size}", chevron = true, onClick = { c.settingsOpen = false; c.pageManagerOpen = true })
        GlassRow("Seite hinzufügen", showDivider = false, onClick = { c.updateLayout { LayoutOps.addPage(it) }; c.showToast("Seite hinzugefügt") })
    }
    SectionHeader("Verhalten")
    GlassGroup {
        SwitchRow("Neue Apps auf Home-Bildschirm", s.addNewAppsToHome, subtitle = "Sonst nur in der App-Bibliothek") { v -> c.set { it.copy(addNewAppsToHome = v) } }
        SwitchRow("Querformat erlauben", s.allowRotation) { v -> c.set { it.copy(allowRotation = v) } }
        SwitchRow("Vorschläge in der Suche", s.searchShowSuggestions, showDivider = false) { v -> c.set { it.copy(searchShowSuggestions = v) } }
    }
    SectionHeader("Uhr-Widget")
    GlassGroup {
        val cs = s.clock
        Column(Modifier.padding(16.dp)) {
            GlassSegmented(listOf("Digital", "Analog"), cs.style.ordinal, { i -> c.set { it.copy(clock = it.clock.copy(style = de.lumen.launcher.data.settings.ClockStyle.entries[i])) } })
        }
        SwitchRow("Sekunden", cs.showSeconds, icon = Icons.Rounded.Schedule, tint = Color(0xFF8E8E93)) { v -> c.set { it.copy(clock = it.clock.copy(showSeconds = v)) } }
        SwitchRow("Datum", cs.showDate, icon = Icons.Rounded.CalendarMonth, tint = Color(0xFFFF3B30), showDivider = false) { v -> c.set { it.copy(clock = it.clock.copy(showDate = v)) } }
    }
}

@Composable
private fun GestureChooser(title: String, value: GestureAction, options: List<GestureAction>, onPick: (GestureAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GlassRow(title, value = value.title, chevron = true, onClick = { open = !open })
    if (open) {
        Column(Modifier.padding(start = 24.dp)) {
            options.forEach { o ->
                GlassRow(o.title, trailing = { if (o == value) Icon(Icons.Rounded.Check, null, tint = Lumen.colors.accent) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

@Composable
private fun GesturesPage(c: LauncherController) {
    val ctx = LocalContext.current
    val g = settings().gestures
    val all = GestureAction.entries
    fun upd(f: (de.lumen.launcher.data.settings.GestureSettings) -> de.lumen.launcher.data.settings.GestureSettings) = c.set { it.copy(gestures = f(it.gestures)) }
    SectionHeader("Wischgesten")
    GlassGroup {
        GestureChooser("Nach oben wischen", g.swipeUp, all) { a -> upd { it.copy(swipeUp = a) } }
        GestureChooser("Nach oben wischen und halten", g.swipeUpHold, all) { a -> upd { it.copy(swipeUpHold = a) } }
        GestureChooser("Nach unten wischen", g.swipeDown, all) { a -> upd { it.copy(swipeDown = a) } }
        GestureChooser("Doppeltippen", g.doubleTap, all) { a -> upd { it.copy(doubleTap = a) } }
    }
    SectionHeader("Bildschirmränder")
    GlassGroup {
        SwitchRow("Oben rechts: Kontrollzentrum", g.topRightControlCenter) { v -> upd { it.copy(topRightControlCenter = v) } }
        SwitchRow("Oben links: Mitteilungen", g.topLeftNotifications) { v -> upd { it.copy(topLeftNotifications = v) } }
        SwitchRow("Haptisches Feedback", g.haptics, showDivider = false) { v -> upd { it.copy(haptics = v) } }
    }
    SectionFooter("Wische unterhalb der Statusleiste nach unten. Der äußerste obere Rand gehört weiterhin Android.")
    SectionHeader("Bildschirm sperren")
    val enabled = LumenLockService.isEnabled(ctx)
    GlassGroup {
        GlassRow(
            "Bedienungshilfe „Lumen Sperren“", icon = Icons.Rounded.Lock, iconTint = Color(0xFF8E8E93),
            value = if (enabled) "Ein" else "Aus", chevron = true, showDivider = false,
            onClick = { ctx.graph.system.start(LumenLockService.settingsIntent()) },
        )
    }
    SectionFooter("Android erlaubt Apps das Sperren des Bildschirms nur über eine Bedienungshilfe (ab Android 9). Lumen liest dabei keine Bildschirminhalte.")
}

@Composable
private fun PermissionsPage(c: LauncherController) {
    val ctx = LocalContext.current
    val sys = ctx.graph.system
    var st by remember { mutableStateOf(LauncherSystem.permissions(ctx)) }
    LaunchedEffect(c.settingsOpen) { st = LauncherSystem.permissions(ctx) }
    @Composable
    fun row(title: String, ok: Boolean, why: String, icon: ImageVector, tint: Color, last: Boolean = false, action: () -> Unit) =
        GlassRow(title, icon = icon, iconTint = tint, subtitle = why, value = if (ok) "Erlaubt" else "Aus", chevron = true, showDivider = !last, onClick = action)
    SectionHeader("Optionale Zugriffe")
    GlassGroup {
        row("Mitteilungszugriff", st.notifications, "Mitteilungszentrale und Mediensteuerung", Icons.Rounded.Notifications, Color(0xFFFF3B30)) { sys.start(NotificationHub.accessIntent(ctx)) }
        row("Systemeinstellungen ändern", st.writeSettings, "Helligkeit und automatisches Drehen", Icons.Rounded.SettingsSuggest, Color(0xFF8E8E93)) { sys.start(sys.intentWriteSettings()) }
        row("Nicht stören", st.dnd, "Schalter im Kontrollzentrum", Icons.Rounded.DoNotDisturbOn, Color(0xFF5E5CE6)) { sys.start(sys.intentDndAccess()) }
        row("Kalender", st.calendar, "Termine im Kalender-Widget", Icons.Rounded.CalendarMonth, Color(0xFFFF9500)) {
            c.bridge?.requestPermission(android.Manifest.permission.READ_CALENDAR) { st = LauncherSystem.permissions(ctx) }
        }
        row("Bedienungshilfe", st.lockService, "Bildschirm per Doppeltippen sperren", Icons.Rounded.Accessibility, Color(0xFF0A84FF), last = true) { sys.start(LumenLockService.settingsIntent()) }
    }
    SectionFooter("Alle Zugriffe sind freiwillig. Lumen funktioniert auch ohne; die jeweilige Funktion zeigt dann einen Hinweis. Es werden keine Daten übertragen – einzige Ausnahme ist die Wetterabfrage für einen von dir gewählten Ort.")
}

@Composable
private fun LibraryPage(c: LauncherController) {
    val lib by c.graph.library.state.collectAsState()
    val apps = LocalApps.current
    SectionHeader("Ausgeblendete Apps")
    GlassGroup {
        val hidden = lib.hidden.mapNotNull { apps[AppKey(it)] }.sortedBy { it.label }
        if (hidden.isEmpty()) GlassRow("Keine ausgeblendeten Apps", icon = Icons.Rounded.Visibility, iconTint = Color(0xFF34C759), showDivider = false)
        hidden.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconImageView(rememberAppIcon(a), 34.dp, sampleWallpaper = false)
                Spacer(Modifier.width(12.dp))
                BasicText(a.label, style = LumenType.body.copy(color = Lumen.colors.textPrimary), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                GlassCapsuleButton("Einblenden", icon = Icons.Rounded.VisibilityOff) { c.graph.library.update { it.copy(hidden = it.hidden - a.key.value) } }
            }
            if (i < hidden.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(0.6.dp).background(Lumen.colors.separator))
        }
    }
    SectionFooter("Apps blendest du über langes Drücken in der App-Bibliothek aus. Sie bleiben installiert und über die Suche nicht sichtbar.")
    SectionHeader("Eigene Kategorien")
    GlassGroup {
        if (lib.customCategories.isEmpty()) GlassRow("Noch keine eigenen Kategorien", icon = Icons.Rounded.Category, iconTint = Color(0xFFBF5AF2), showDivider = false)
        lib.customCategories.forEachIndexed { i, cc ->
            GlassRow(cc.name, subtitle = "${cc.apps.size} Apps", showDivider = i < lib.customCategories.lastIndex, trailing = {
                Icon(Icons.Rounded.Delete, "Löschen", tint = Lumen.colors.destructive, modifier = Modifier.size(22.dp).pointerInput(cc.id) {
                    detectTapGestures { c.graph.library.update { st -> st.copy(customCategories = st.customCategories.filterNot { it.id == cc.id }) } }
                })
            })
        }
    }
    SectionFooter("Eigene Kategorien legst du über „Kategorie ändern“ im Kontextmenü einer App an.")
    SectionHeader("Nutzungsdaten")
    GlassGroup {
        GlassRow("Vorschläge zurücksetzen", icon = Icons.Rounded.Restore, iconTint = Color(0xFF8E8E93), showDivider = false, onClick = {
            c.graph.library.update { it.copy(usage = emptyMap()) }; c.showToast("Vorschläge zurückgesetzt")
        })
    }
}

@Composable
private fun AboutPage(c: LauncherController) {
    val ctx = LocalContext.current
    SectionHeader("Lumen")
    GlassGroup {
        GlassRow("Version", value = LauncherSystem.versionName(ctx))
        GlassRow("Schrift", value = "Inter (SIL OFL 1.1)")
        GlassRow("Wetterdaten", value = "Open-Meteo", showDivider = false)
    }
    SectionFooter("Lumen ist ein eigenständiger Launcher, inspiriert von moderner Glas-Designsprache. Er verwendet ausschließlich offizielle Android-Schnittstellen und benötigt keine Root-Rechte.")
    SectionHeader("Zurücksetzen")
    GlassGroup {
        GlassRow("Home-Bildschirm neu anordnen", icon = Icons.Rounded.Restore, iconTint = Color(0xFFFF3B30), subtitle = "Erstellt die Standard-Anordnung neu. Apps bleiben installiert.", destructive = true, showDivider = false, onClick = {
            c.graph.layout.value.allItems().filterIsInstance<de.lumen.launcher.data.layout.NativeWidgetItem>().forEach { ctx.graph.widgetHost.delete(it.appWidgetId) }
            c.graph.layout.set(de.lumen.launcher.data.layout.HomeLayout())
            ctx.graph.appSync.resync()
            c.settingsOpen = false
            c.showToast("Home-Bildschirm wurde neu angeordnet")
        })
    }
}
