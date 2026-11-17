package de.lumen.launcher.ui.control

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.graph
import de.lumen.launcher.system.MediaHub
import de.lumen.launcher.system.NetworkKind
import de.lumen.launcher.system.NotificationHub
import de.lumen.launcher.system.ScreenRecordService
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.Overlay
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.GlassVerticalSlider
import de.lumen.launcher.ui.glass.OverlayFrame
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.glass.liquidPress
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType

private val TileCorner = 30.dp

@Composable
fun ControlCenter(c: LauncherController, backdrop: Backdrop) {
    val anim = c.anim(Overlay.CONTROL_CENTER)
    if (!anim.visible) return
    val ctx = LocalContext.current
    val sys = ctx.graph.system
    val st by sys.state.collectAsState()
    val recording by ScreenRecordService.recording.collectAsState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(anim.target) { if (anim.target > 0f) sys.refresh() }

    fun needs(intent: Intent, msg: String) {
        c.showToast(msg)
        sys.start(intent)
    }

    OverlayFrame(backdrop, { anim.progress.value }, blur = 56.dp, scrim = Color.Black.copy(alpha = 0.32f)) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { c.close(Overlay.CONTROL_CENTER) } }
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = { if (acc < -120f) c.close(Overlay.CONTROL_CENTER) else c.open(Overlay.CONTROL_CENTER) },
                        onVerticalDrag = { ch, dy ->
                            ch.consume(); acc = (acc + dy).coerceAtMost(0f)
                            c.dragOverlay(Overlay.CONTROL_CENTER, 1f + acc / 900f)
                        },
                    )
                },
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(top = statusTop + 28.dp, start = 22.dp, end = 22.dp),
        ) {
            val gap = 14.dp
            val cell = (maxWidth - gap * 3) / 4
            Column(
                Modifier.graphicsLayer {
                    val p = anim.progress.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0f)
                    val s = 0.82f + 0.18f * p
                    scaleX = s; scaleY = s
                    alpha = p
                },
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                // ---------------------------------------------------- connectivity + media
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(
                        Modifier.size(cell * 2 + gap).glass(SmoothRoundedShape(TileCorner), GlassStyle(sampleWallpaper = false, elevation = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = cell * 0.78f
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                CircleButton(Icons.Rounded.AirplanemodeActive, st.airplane, Color(0xFFFF9F0A), b, "Flugmodus") { sys.start(sys.intentAirplane()) }
                                CircleButton(Icons.Rounded.CellTower, st.network == NetworkKind.CELLULAR, Color(0xFF30D158), b, "Mobile Daten") { sys.start(sys.intentInternet()) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                CircleButton(Icons.Rounded.Wifi, st.wifiEnabled, Color(0xFF0A84FF), b, "WLAN") { sys.start(sys.intentWifi()) }
                                CircleButton(Icons.Rounded.Bluetooth, st.bluetoothEnabled, Color(0xFF0A84FF), b, "Bluetooth") { sys.start(sys.intentBluetooth()) }
                            }
                        }
                    }
                    MediaTile(c, cell * 2 + gap)
                }
                // ---------------------------------------------------- toggles + sliders
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            CircleButton(
                                if (st.autoRotate) Icons.Rounded.ScreenRotation else Icons.Rounded.ScreenLockRotation,
                                !st.autoRotate, Color(0xFFFF453A), cell, if (st.autoRotate) "Automatisch drehen" else "Ausrichtung gesperrt",
                            ) {
                                if (!sys.setAutoRotate(!st.autoRotate)) needs(sys.intentWriteSettings(), "Bitte „Systemeinstellungen ändern“ für Lumen erlauben")
                            }
                            CircleButton(Icons.Rounded.FlashlightOn, st.torchOn, Color.White, cell, "Taschenlampe", enabled = st.torchAvailable, activeIcon = Color(0xFF1C1C1E)) {
                                if (!sys.setTorch(!st.torchOn)) c.showToast("Taschenlampe nicht verfügbar")
                            }
                        }
                        WideTile(
                            icon = Icons.Rounded.DarkMode,
                            title = "Nicht stören",
                            subtitle = if (st.dndOn) "Ein" else "Aus",
                            active = st.dndOn,
                            activeColor = Color(0xFF5E5CE6),
                            width = cell * 2 + gap,
                            height = cell,
                        ) {
                            if (!sys.setDnd(!st.dndOn)) needs(sys.intentDndAccess(), "Bitte Lumen Zugriff auf „Nicht stören“ geben")
                        }
                    }
                    var bright by remember { mutableFloatStateOf(st.brightness) }
                    LaunchedEffect(st.brightness) { bright = st.brightness }
                    var warned by remember { mutableFloatStateOf(0f) }
                    GlassVerticalSlider(
                        bright,
                        onChange = {
                            bright = it
                            if (!sys.setBrightness(it) && warned == 0f) {
                                warned = 1f
                                needs(sys.intentWriteSettings(), "Bitte „Systemeinstellungen ändern“ für Lumen erlauben")
                            }
                        },
                        icon = Icons.Rounded.LightMode,
                        modifier = Modifier.size(cell, cell * 2 + gap),
                        onChangeFinished = { warned = 0f },
                    )
                    var vol by remember { mutableFloatStateOf(st.volume) }
                    LaunchedEffect(st.volume) { vol = st.volume }
                    GlassVerticalSlider(
                        vol,
                        onChange = { vol = it; sys.setVolume(it) },
                        icon = Icons.Rounded.VolumeUp,
                        modifier = Modifier.size(cell, cell * 2 + gap),
                    )
                }
                // ---------------------------------------------------- shortcuts
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircleButton(Icons.Rounded.BatterySaver, st.powerSave, Color(0xFFFFD60A), cell, "Energiesparmodus", activeIcon = Color(0xFF1C1C1E)) { sys.start(sys.intentBatterySaver()) }
                    CircleButton(Icons.Rounded.RadioButtonChecked, recording, Color(0xFFFF3B30), cell, "Bildschirmaufnahme") {
                        c.close(Overlay.CONTROL_CENTER)
                        c.bridge?.startScreenRecording()
                    }
                    CircleButton(Icons.Rounded.CameraAlt, false, Color.White, cell, "Kamera") {
                        c.close(Overlay.CONTROL_CENTER)
                        sys.start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    }
                    CircleButton(Icons.Rounded.Settings, false, Color.White, cell, "Einstellungen") {
                        c.close(Overlay.CONTROL_CENTER)
                        sys.start(sys.intentSettings())
                    }
                }
                Spacer(Modifier.height(4.dp))
                BasicText(
                    "WLAN, Bluetooth, Flugmodus, mobile Daten und Energiesparen öffnen das System-Panel – Android lässt Apps diese nicht direkt schalten.",
                    style = LumenType.caption.copy(color = Color.White.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CircleButton(
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    size: Dp,
    description: String,
    enabled: Boolean = true,
    activeIcon: Color = Color.White,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) activeColor else Color.White.copy(alpha = 0.10f), Lumen.motion.fade(), label = "cc")
    Box(
        Modifier
            .size(size)
            .glass(CircleShape, GlassStyle(sampleWallpaper = false, tint = bg, elevation = 0.6f))
            .clip(CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .liquidPress(enabled = enabled, pressScale = 0.9f, shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) activeIcon else Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun WideTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    activeColor: Color,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f), Lumen.motion.fade(), label = "wide")
    Row(
        Modifier
            .size(width, height)
            .glass(CircleShape, GlassStyle(sampleWallpaper = false, tint = bg, elevation = 0.6f))
            .clip(CircleShape)
            .liquidPress(pressScale = 0.95f, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(height * 0.62f).clip(CircleShape).background(if (active) activeColor else Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(height * 0.32f)) }
        Spacer(Modifier.width(10.dp))
        Column {
            BasicText(title, style = LumenType.subhead.copy(color = if (active) Color(0xFF1C1C1E) else Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(subtitle, style = LumenType.caption.copy(color = if (active) Color(0xFF3A3A3C) else Color.White.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun MediaTile(c: LauncherController, size: Dp) {
    val ctx = LocalContext.current
    val media by MediaHub.media.collectAsState()
    val connected by NotificationHub.connected.collectAsState()
    Box(
        Modifier
            .size(size)
            .glass(SmoothRoundedShape(TileCorner), GlassStyle(sampleWallpaper = false, elevation = 0.7f))
            .clip(SmoothRoundedShape(TileCorner))
            .pointerInput(media?.packageName, connected) {
                detectTapGestures {
                    when {
                        media != null -> { c.close(Overlay.CONTROL_CENTER); MediaHub.openApp(ctx) }
                        !connected -> { c.showToast("Für Mediensteuerung bitte Mitteilungszugriff erlauben"); ctx.graph.system.start(NotificationHub.accessIntent(ctx)) }
                    }
                }
            }
            .padding(14.dp),
    ) {
        val m = media
        if (m == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    if (connected) "Keine Wiedergabe" else "Mitteilungszugriff für Mediensteuerung erlauben",
                    style = LumenType.footnote.copy(color = Color.White.copy(alpha = 0.75f), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(SmoothRoundedShape(10.dp)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        if (m.art != null) Image(m.art, null, Modifier.fillMaxSize()) else Icon(Icons.Rounded.MusicNote, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                BasicText(m.title.ifBlank { "Unbekannter Titel" }, style = LumenType.subhead.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(m.artist, style = LumenType.footnote.copy(color = Color.White.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    MediaButton(Icons.Rounded.FastRewind, 26.dp) { MediaHub.previous() }
                    MediaButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 34.dp) { MediaHub.playPause() }
                    MediaButton(Icons.Rounded.FastForward, 26.dp) { MediaHub.next() }
                }
            }
        }
    }
}

@Composable
fun MediaButton(icon: ImageVector, size: Dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 14.dp).clip(CircleShape).liquidPress(pressScale = 0.85f, shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size)) }
}
