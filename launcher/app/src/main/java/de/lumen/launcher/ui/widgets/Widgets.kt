package de.lumen.launcher.ui.widgets

import android.Manifest
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.LibraryModel
import de.lumen.launcher.data.apps.UsageRanking
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize
import de.lumen.launcher.graph
import de.lumen.launcher.system.MediaHub
import de.lumen.launcher.system.NotificationHub
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.PendingDrag
import de.lumen.launcher.ui.control.MediaButton
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.home.IconImageView
import de.lumen.launcher.ui.home.LocalApps
import de.lumen.launcher.ui.home.pendingWidgetEdit
import de.lumen.launcher.ui.home.rememberAppIcon
import de.lumen.launcher.ui.theme.InterDisplay
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.widgets.CalendarEvent
import de.lumen.launcher.widgets.WeatherCodes
import de.lumen.launcher.widgets.WeatherData
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

private val W = Color.White
private val W2 = Color.White.copy(alpha = 0.72f)

/** Opens the reminders sheet (set from the widget, rendered by the overlay host). */
var remindersSheetOpen by mutableStateOf(false)

@Composable
fun BuiltinWidget(c: LauncherController, item: WidgetItem) {
    when (item.kind) {
        WidgetKind.CLOCK -> ClockWidget(item)
        WidgetKind.DATE -> DateWidget(item)
        WidgetKind.WEATHER -> WeatherWidget(item)
        WidgetKind.CALENDAR -> CalendarWidget(c, item)
        WidgetKind.BATTERY -> BatteryWidget(item)
        WidgetKind.REMINDERS -> RemindersWidget(c, item)
        WidgetKind.MUSIC -> MusicWidget(c, item)
        WidgetKind.SHORTCUTS -> ShortcutsWidget(c, item)
    }
}

@Composable
private fun WidgetTitle(text: String, color: Color = Lumen.colors.accent) {
    BasicText(text.uppercase(Locale.GERMANY), style = LumenType.caption2.copy(color = androidx.compose.ui.graphics.lerp(color, W, 0.3f), fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp))
}

// ============================================================================ weather

fun weatherIcon(code: Int, day: Boolean): ImageVector = when (WeatherCodes.kind(code)) {
    WeatherCodes.Kind.CLEAR -> if (day) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    WeatherCodes.Kind.PARTLY -> Icons.Rounded.WbCloudy
    WeatherCodes.Kind.CLOUDY -> Icons.Rounded.Cloud
    WeatherCodes.Kind.FOG -> Icons.Rounded.BlurOn
    WeatherCodes.Kind.DRIZZLE -> Icons.Rounded.Grain
    WeatherCodes.Kind.RAIN -> Icons.Rounded.WaterDrop
    WeatherCodes.Kind.SNOW -> Icons.Rounded.AcUnit
    WeatherCodes.Kind.STORM -> Icons.Rounded.FlashOn
}

private fun weatherTint(code: Int, day: Boolean): Brush {
    val (a, b) = when {
        !day -> Color(0xFF1B2A55) to Color(0xFF0B1230)
        WeatherCodes.kind(code) == WeatherCodes.Kind.CLEAR -> Color(0xFF3A8DFF) to Color(0xFF1D5FD1)
        WeatherCodes.kind(code) in setOf(WeatherCodes.Kind.RAIN, WeatherCodes.Kind.DRIZZLE, WeatherCodes.Kind.STORM) -> Color(0xFF4D6178) to Color(0xFF2B3A4C)
        WeatherCodes.kind(code) == WeatherCodes.Kind.SNOW -> Color(0xFF8FA7C2) to Color(0xFF5E7894)
        else -> Color(0xFF5F86B5) to Color(0xFF3C5C86)
    }
    return Brush.verticalGradient(listOf(a.copy(alpha = 0.55f), b.copy(alpha = 0.55f)))
}

@Composable
private fun WeatherWidget(item: WidgetItem) {
    val ctx = LocalContext.current
    val repo = ctx.graph.weather
    val lat = item.config["lat"]?.toDoubleOrNull()
    val lon = item.config["lon"]?.toDoubleOrNull()
    val city = item.config["city"]
    val all by repo.data.collectAsState()
    if (lat == null || lon == null || city == null) {
        Column(
            Modifier.fillMaxSize().pointerInput(item.id) { detectTapGestures { pendingWidgetEdit = item.id } }.padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.LocationOn, null, tint = W, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(6.dp))
            BasicText("Ort festlegen", style = LumenType.headline.copy(color = W))
            BasicText("Tippen, um eine Stadt zu wählen", style = LumenType.caption.copy(color = W2))
        }
        return
    }
    LaunchedEffect(lat, lon) { repo.refresh(lat, lon) }
    val data = all[repo.key(lat, lon)]
    Box(Modifier.fillMaxSize().background(data?.let { weatherTint(it.code, it.isDay) } ?: Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))) {
        if (data == null) {
            Column(Modifier.padding(16.dp)) {
                BasicText(city, style = LumenType.headline.copy(color = W))
                BasicText("Wetter wird geladen …", style = LumenType.footnote.copy(color = W2))
            }
        } else when (item.size) {
            WidgetSize.SMALL -> WeatherSmall(city, data)
            WidgetSize.MEDIUM -> WeatherMedium(city, data)
            WidgetSize.LARGE -> WeatherLarge(city, data)
        }
    }
}

@Composable
private fun WeatherHeader(city: String, d: WeatherData, big: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(city, style = LumenType.headline.copy(color = W), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Rounded.LocationOn, null, tint = W, modifier = Modifier.size(13.dp))
        }
        BasicText("${d.temp.roundToInt()}°", style = LumenType.largeTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Light, fontSize = if (big) 50.sp else 44.sp, lineHeight = 52.sp, color = W))
    }
}

@Composable
private fun WeatherSummary(d: WeatherData) {
    val today = d.daily.firstOrNull()
    Column {
        Icon(weatherIcon(d.code, d.isDay), null, tint = W, modifier = Modifier.size(20.dp))
        BasicText(WeatherCodes.describe(d.code), style = LumenType.footnote.copy(color = W, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (today != null) BasicText("H:${today.max.roundToInt()}° T:${today.min.roundToInt()}°", style = LumenType.footnote.copy(color = W, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun WeatherSmall(city: String, d: WeatherData) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        WeatherHeader(city, d, false)
        Spacer(Modifier.weight(1f))
        WeatherSummary(d)
    }
}

@Composable
private fun HourRow(d: WeatherData, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        d.hourly.take(6).forEachIndexed { i, h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(if (i == 0) "Jetzt" else h.time.substringAfter('T').take(2), style = LumenType.caption2.copy(color = W2))
                Spacer(Modifier.height(4.dp))
                val hour = h.time.substringAfter('T').take(2).toIntOrNull() ?: 12
                Icon(weatherIcon(h.code, if (i == 0) d.isDay else hour in 7..18), null, tint = W, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(4.dp))
                BasicText("${h.temp.roundToInt()}°", style = LumenType.footnote.copy(color = W, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun WeatherMedium(city: String, d: WeatherData) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { WeatherHeader(city, d, false) }
            Box(Modifier.padding(top = 2.dp)) { WeatherSummary(d) }
        }
        Spacer(Modifier.weight(1f))
        HourRow(d)
    }
}

@Composable
private fun WeatherLarge(city: String, d: WeatherData) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dayFmt = remember { SimpleDateFormat("EEE", Locale.GERMANY) }
    val minAll = d.daily.minOfOrNull { it.min } ?: 0.0
    val maxAll = d.daily.maxOfOrNull { it.max } ?: 1.0
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { WeatherHeader(city, d, true) }
            WeatherSummary(d)
        }
        Spacer(Modifier.height(10.dp))
        HourRow(d)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(W.copy(alpha = 0.3f)))
        d.daily.take(5).forEachIndexed { i, day ->
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val name = if (i == 0) "Heute" else runCatching { dayFmt.format(fmt.parse(day.date)!!) }.getOrDefault(day.date)
                BasicText(name, style = LumenType.subhead.copy(color = W, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(56.dp))
                Icon(weatherIcon(day.code, true), null, tint = W, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                BasicText("${day.min.roundToInt()}°", style = LumenType.subhead.copy(color = W2), modifier = Modifier.width(34.dp))
                TempBar(day.min, day.max, minAll, maxAll, Modifier.weight(1f))
                BasicText("${day.max.roundToInt()}°", style = LumenType.subhead.copy(color = W), modifier = Modifier.padding(start = 8.dp).width(34.dp))
            }
        }
    }
}

@Composable
private fun TempBar(min: Double, max: Double, lo: Double, hi: Double, modifier: Modifier) {
    Canvas(modifier.height(5.dp)) {
        val range = (hi - lo).coerceAtLeast(1.0)
        val s = ((min - lo) / range).toFloat()
        val e = ((max - lo) / range).toFloat()
        drawRoundRect(W.copy(alpha = 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF7BD3FF), Color(0xFFFFD36B))),
            topLeft = Offset(size.width * s, 0f), size = Size(size.width * (e - s).coerceAtLeast(0.04f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
        )
    }
}

// ============================================================================ calendar

@Composable
private fun CalendarWidget(c: LauncherController, item: WidgetItem) {
    val ctx = LocalContext.current
    val repo = ctx.graph.calendar
    var granted by remember { mutableStateOf(repo.hasPermission()) }
    LaunchedEffect(granted) { repo.refresh() }
    val events by repo.events.collectAsState()
    val now = rememberNow(false)
    val accent = Lumen.colors.destructive
    if (!granted) {
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures {
                    c.bridge?.requestPermission(Manifest.permission.READ_CALENDAR) { ok -> granted = ok; if (ok) repo.refresh() }
                }
            }.padding(16.dp),
        ) {
            WidgetTitle(SimpleDateFormat("EEEE", Locale.GERMANY).format(now), accent)
            BasicText(SimpleDateFormat("d", Locale.GERMANY).format(now), style = LumenType.largeTitle.copy(fontWeight = FontWeight.Light, fontSize = 40.sp, color = W))
            Spacer(Modifier.weight(1f))
            BasicText("Tippen, um Kalenderzugriff zu erlauben", style = LumenType.caption.copy(color = W2))
        }
        return
    }
    val list = events.take(if (item.size == WidgetSize.LARGE) 7 else 3)
    when (item.size) {
        WidgetSize.SMALL -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            WidgetTitle(SimpleDateFormat("EEEE", Locale.GERMANY).format(now), accent)
            BasicText(SimpleDateFormat("d", Locale.GERMANY).format(now), style = LumenType.largeTitle.copy(fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 44.sp, color = W))
            Spacer(Modifier.weight(1f))
            val next = list.firstOrNull()
            if (next == null) BasicText("Keine Termine", style = LumenType.footnote.copy(color = W2))
            else EventRow(repo, next, compact = true)
        }
        else -> Row(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.width(if (item.size == WidgetSize.LARGE) 90.dp else 76.dp)) {
                WidgetTitle(SimpleDateFormat("EEEE", Locale.GERMANY).format(now), accent)
                BasicText(SimpleDateFormat("d", Locale.GERMANY).format(now), style = LumenType.largeTitle.copy(fontWeight = FontWeight.Light, fontSize = 44.sp, lineHeight = 48.sp, color = W))
                BasicText(SimpleDateFormat("MMMM", Locale.GERMANY).format(now), style = LumenType.footnote.copy(color = W2))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) BasicText("Keine Termine in den nächsten 7 Tagen", style = LumenType.footnote.copy(color = W2))
                list.forEach { EventRow(repo, it, compact = false) }
            }
        }
    }
}

@Composable
private fun EventRow(repo: de.lumen.launcher.widgets.CalendarRepository, e: CalendarEvent, compact: Boolean) {
    val time = if (e.allDay) "Ganztägig" else {
        val sameDay = android.text.format.DateUtils.isToday(e.begin)
        (if (sameDay) "" else SimpleDateFormat("EEE ", Locale.GERMANY).format(e.begin)) + SimpleDateFormat("HH:mm", Locale.GERMANY).format(e.begin)
    }
    Row(Modifier.pointerInput(e.id) { detectTapGestures { repo.openEvent(e) } }) {
        Box(Modifier.width(3.dp).height(if (compact) 32.dp else 34.dp).clip(CircleShape).background(Color(e.color or 0xFF000000.toInt())))
        Spacer(Modifier.width(7.dp))
        Column {
            BasicText(e.title, style = LumenType.footnote.copy(color = W, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(time, style = LumenType.caption.copy(color = W2), maxLines = 1)
        }
    }
}

// ============================================================================ battery

@Composable
private fun BatteryWidget(item: WidgetItem) {
    val ctx = LocalContext.current
    val info by ctx.graph.battery.info.collectAsState()
    val level = info.level.coerceAtLeast(0)
    val color = when {
        info.charging -> Lumen.colors.success
        level <= 20 -> Lumen.colors.destructive
        else -> Lumen.colors.success
    }
    @Composable
    fun Ring(mod: Modifier) {
        Box(mod.aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = size.minDimension * 0.1f
                drawArc(W.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw))
                drawArc(color, -90f, 360f * level / 100f, false, style = Stroke(sw, cap = StrokeCap.Round), topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw))
            }
            Icon(if (info.charging) Icons.Rounded.Bolt else Icons.Rounded.Smartphone, null, tint = W, modifier = Modifier.fillMaxSize(0.38f))
        }
    }
    when (item.size) {
        WidgetSize.SMALL -> Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Ring(Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
            BasicText(if (info.level < 0) "–" else "$level %", style = LumenType.title3.copy(color = W))
        }
        else -> Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Ring(Modifier.fillMaxHeight())
            Spacer(Modifier.width(18.dp))
            Column {
                WidgetTitle("Batterie", color)
                BasicText(if (info.level < 0) "–" else "$level %", style = LumenType.largeTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Light, color = W))
                BasicText(
                    when {
                        info.charging && level >= 100 -> "Vollständig geladen"
                        info.charging -> "Wird geladen"
                        info.plugged -> "Angeschlossen"
                        else -> "Akkubetrieb"
                    } + if (info.temperatureC > 0f) " · ${"%.0f".format(info.temperatureC)} °C" else "",
                    style = LumenType.footnote.copy(color = W2),
                )
            }
        }
    }
}

// ============================================================================ reminders

@Composable
private fun RemindersWidget(c: LauncherController, item: WidgetItem) {
    val ctx = LocalContext.current
    val store = ctx.graph.reminders
    val list by store.state.collectAsState()
    val open = list.items.filter { !it.done }
    val max = when (item.size) { WidgetSize.SMALL -> 3; WidgetSize.MEDIUM -> 3; WidgetSize.LARGE -> 9 }
    val accent = Lumen.colors.warning
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                WidgetTitle("Erinnerungen", accent)
                BasicText("${open.size}", style = LumenType.title1.copy(color = W))
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(W.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures { c.haptic(Haptic.TICK); remindersSheetOpen = true } },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "Erinnerung hinzufügen", tint = W, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        if (open.isEmpty()) {
            BasicText("Alles erledigt", style = LumenType.footnote.copy(color = W2))
        }
        open.take(max).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp).border(1.5.dp, accent, CircleShape)
                        .pointerInput(r.id) {
                            detectTapGestures {
                                c.haptic(Haptic.CONFIRM)
                                store.update { st -> st.copy(items = st.items.map { if (it.id == r.id) it.copy(done = true) else it }) }
                            }
                        }
                )
                Spacer(Modifier.width(8.dp))
                BasicText(r.text, style = LumenType.footnote.copy(color = W), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { remindersSheetOpen = true } })
            }
        }
    }
}

// ============================================================================ music

@Composable
private fun MusicWidget(c: LauncherController, item: WidgetItem) {
    val ctx = LocalContext.current
    val media by MediaHub.media.collectAsState()
    val connected by NotificationHub.connected.collectAsState()
    val m = media
    if (m == null) {
        Column(
            Modifier.fillMaxSize().pointerInput(connected) {
                detectTapGestures { if (!connected) ctx.graph.system.start(NotificationHub.accessIntent(ctx)) }
            }.padding(16.dp),
        ) {
            Box(Modifier.size(46.dp).clip(SmoothRoundedShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF5E7E), Color(0xFFFA2D48)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = W)
            }
            Spacer(Modifier.weight(1f))
            BasicText("Nicht aktiv", style = LumenType.headline.copy(color = W))
            BasicText(if (connected) "Starte die Wiedergabe in einer App" else "Mitteilungszugriff für Mediensteuerung erlauben", style = LumenType.caption.copy(color = W2))
        }
        return
    }
    val art: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.clip(SmoothRoundedShape(12.dp)).background(W.copy(alpha = 0.15f)).pointerInput(Unit) { detectTapGestures { MediaHub.openApp(ctx) } }, contentAlignment = Alignment.Center) {
            if (m.art != null) Image(m.art, null, Modifier.fillMaxSize()) else Icon(Icons.Rounded.MusicNote, null, tint = W)
        }
    }
    when (item.size) {
        WidgetSize.SMALL -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            art(Modifier.size(54.dp))
            Spacer(Modifier.weight(1f))
            BasicText(m.title, style = LumenType.footnote.copy(color = W, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(m.artist, style = LumenType.caption.copy(color = W2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MediaButton(Icons.Rounded.FastRewind, 20.dp) { MediaHub.previous() }
                MediaButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 24.dp) { MediaHub.playPause() }
                MediaButton(Icons.Rounded.FastForward, 20.dp) { MediaHub.next() }
            }
        }
        else -> Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.fillMaxHeight().aspectRatio(1f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                WidgetTitle(if (m.playing) "Wird abgespielt" else "Pausiert", Color(0xFFFF375F))
                BasicText(m.title, style = LumenType.headline.copy(color = W), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(m.artist, style = LumenType.subhead.copy(color = W2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MediaButton(Icons.Rounded.FastRewind, 24.dp) { MediaHub.previous() }
                    MediaButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 30.dp) { MediaHub.playPause() }
                    MediaButton(Icons.Rounded.FastForward, 24.dp) { MediaHub.next() }
                }
            }
        }
    }
}

// ============================================================================ shortcuts

@Composable
private fun ShortcutsWidget(c: LauncherController, item: WidgetItem) {
    val apps = LocalApps.current
    val lib by c.graph.library.state.collectAsState()
    val configured = item.config["apps"]?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { apps[AppKey(it)] }.orEmpty()
    val count = when (item.size) { WidgetSize.SMALL -> 4; WidgetSize.MEDIUM -> 8; WidgetSize.LARGE -> 16 }
    val list = configured.ifEmpty {
        val visible = LibraryModel.visibleApps(apps.values.toList(), lib).associateBy { it.key.value }
        UsageRanking.suggestions(lib.usage, System.currentTimeMillis(), count).mapNotNull { visible[it] }
            .ifEmpty { visible.values.take(count) }
    }.take(count)
    val cols = if (item.size == WidgetSize.SMALL) 2 else 4
    BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp)) {
        val rows = (count / cols)
        val cell = minOf(maxWidth / cols, maxHeight / rows)
        val icon = cell * 0.78f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            list.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { a ->
                        Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                            IconImageView(
                                rememberAppIcon(a), icon,
                                Modifier.pointerInput(a.key) { detectTapGestures { c.launch(a, null) } },
                                sampleWallpaper = false,
                            )
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.size(cell)) }
                }
            }
        }
    }
}

// ============================================================================ native app widgets

@Composable
fun NativeWidget(c: LauncherController, item: NativeWidgetItem) {
    val ctx = LocalContext.current
    val host = ctx.graph.widgetHost
    val info = remember(item.appWidgetId) { host.info(item.appWidgetId) }
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val shape = SmoothRoundedShape(de.lumen.launcher.ui.home.HomeDimens.widgetCorner)
    if (info == null) {
        Box(
            Modifier.fillMaxSize().glass(shape, GlassStyle(elevation = 0.6f)).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Widgets, null, tint = W2)
                BasicText("Widget nicht verfügbar", style = LumenType.footnote.copy(color = W2))
            }
        }
        return
    }
    val editMode = c.editMode
    AndroidView(
        factory = {
            host.view(item.appWidgetId, info).also { v -> (v.parent as? ViewGroup)?.removeView(v) }
        },
        update = { v ->
            v.editMode = editMode
            v.onLongPress = { x, y ->
                c.haptic(Haptic.LONG_PRESS)
                c.menu = MenuState(MenuTarget.Widget(item), bounds)
                c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Widget(item), bounds, Offset(bounds.left + x, bounds.top + y), item.id, item.cell.w to item.cell.h))
            }
            v.onEditPress = { x, y ->
                c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Widget(item), bounds, Offset(bounds.left + x, bounds.top + y), item.id, item.cell.w to item.cell.h))
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .onSizeChanged { s -> with(density) { host.updateSize(item.appWidgetId, s.width.toDp().value.toInt(), s.height.toDp().value.toInt()) } },
    )
}
