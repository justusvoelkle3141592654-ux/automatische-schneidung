package de.lumen.launcher.ui.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOn
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize
import de.lumen.launcher.data.settings.ClockStyle
import de.lumen.launcher.graph
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSearchField
import de.lumen.launcher.ui.glass.GlassSegmented
import de.lumen.launcher.ui.glass.GlassSheet
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.GlassSwitch
import de.lumen.launcher.ui.glass.SectionFooter
import de.lumen.launcher.ui.glass.SectionHeader
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.home.IconImageView
import de.lumen.launcher.ui.home.LocalApps
import de.lumen.launcher.ui.home.pendingWidgetEdit
import de.lumen.launcher.ui.home.rememberAppIcon
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.widgets.Place
import de.lumen.launcher.widgets.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ============================================================================ widget gallery

@Composable
fun WidgetPickerSheet(c: LauncherController, backdrop: Backdrop) {
    val s = Lumen.settings
    GlassSheet(c.widgetPickerOpen, { c.widgetPickerOpen = false }, backdrop, title = "Widgets", maxHeightFraction = 0.92f) {
        val ctx = LocalContext.current
        val host = ctx.graph.widgetHost
        val providers by produceState<List<Pair<AppWidgetProviderInfo, Pair<String, ImageBitmap?>>>>(emptyList(), c.widgetPickerOpen) {
            if (!c.widgetPickerOpen) return@produceState
            value = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                val dens = ctx.resources.displayMetrics.densityDpi
                host.providers().map { p ->
                    val label = runCatching { p.loadLabel(pm) }.getOrNull() ?: p.provider.className.substringAfterLast('.')
                    val preview = runCatching { (p.loadPreviewImage(ctx, dens) ?: p.loadIcon(ctx, dens))?.toBitmap(360, 240)?.asImageBitmap() }.getOrNull()
                    p to (label to preview)
                }.sortedBy { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it.first.provider.packageName, 0)).toString() }.getOrDefault("") + it.second.first }
            }
        }
        fun pageForAdd() = c.pager?.currentPage ?: 0

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Lumen-Widgets", Modifier.padding(start = 20.dp)) }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(WidgetKind.entries.toList()) { kind ->
                        var size by remember { mutableStateOf(if (kind == WidgetKind.CLOCK) WidgetSize.MEDIUM else WidgetSize.SMALL) }
                        Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                                val w = if (size.w == 2) 120.dp else 210.dp
                                val h = if (size.h == 2) 120.dp else 210.dp
                                val scale = if (size == WidgetSize.LARGE) 0.8f else 1f
                                Box(
                                    Modifier
                                        .size(w * scale, h * scale)
                                        .glass(SmoothRoundedShape(20.dp), GlassStyle(sampleWallpaper = false, tint = Color(0x662C2C2E), elevation = 0.8f))
                                        .clip(SmoothRoundedShape(20.dp)),
                                ) {
                                    BuiltinWidget(c, WidgetItem(-1, de.lumen.launcher.data.layout.Cell(0, 0, size.w, size.h), kind, size))
                                }
                            }
                            BasicText(kind.title, style = LumenType.headline.copy(color = Lumen.colors.textPrimary))
                            Spacer(Modifier.height(6.dp))
                            GlassSegmented(WidgetSize.entries.map { it.title }, size.ordinal, { size = WidgetSize.entries[it] })
                            Spacer(Modifier.height(8.dp))
                            GlassCapsuleButton("Hinzufügen", prominent = true) {
                                val (l, ok) = LayoutOps.addWidget(c.graph.layout.value, pageForAdd(), kind, size, s.gridColumns, s.gridRows)
                                if (ok) {
                                    c.graph.layout.set(l); c.widgetPickerOpen = false; c.haptic(Haptic.CONFIRM)
                                    if (kind == WidgetKind.WEATHER) pendingWidgetEdit = l.pages[pageForAdd()].items.maxOf { it.id }
                                } else c.showToast("Auf dieser Seite ist nicht genug Platz")
                            }
                        }
                    }
                }
            }
            item { SectionHeader("App-Widgets", Modifier.padding(start = 20.dp)) }
            if (providers.isEmpty()) {
                item { SectionFooter("Keine weiteren Widgets installiert.", Modifier.padding(start = 4.dp)) }
            }
            items(providers, key = { it.first.provider.flattenToString() + it.first.profile.hashCode() }) { (info, meta) ->
                val (label, preview) = meta
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .glass(SmoothRoundedShape(18.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.fill, elevation = 0f))
                        .pointerInput(info) { detectTapGestures { addNativeWidget(c, info, pageForAdd()) } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(96.dp, 64.dp).clip(SmoothRoundedShape(10.dp)).background(Lumen.colors.fill), contentAlignment = Alignment.Center) {
                        if (preview != null) Image(preview, null, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(label, style = LumenType.body.copy(color = Lumen.colors.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val appName = remember(info) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName) }
                        BasicText(appName, style = LumenType.footnote.copy(color = Lumen.colors.textSecondary))
                    }
                }
            }
        }
    }
}

/** Allocate → bind (with system consent if needed) → configure → place. Cleans up on failure. */
fun addNativeWidget(c: LauncherController, info: AppWidgetProviderInfo, page: Int) {
    val host = c.graph.widgetHost
    val s = c.graph.settings.value
    val id = host.allocate()
    fun place() {
        val g = c.geometry
        val d = c.view?.resources?.displayMetrics?.density ?: 3f
        val (w, h) = host.span(info, g.cellSize.width / d, g.cellSize.height / d, s.gridColumns, s.gridRows)
        val (l, ok) = LayoutOps.addNativeWidget(c.graph.layout.value, page, id, info.provider.flattenToString(), w, h, s.gridColumns, s.gridRows)
        if (ok) { c.graph.layout.set(l); c.widgetPickerOpen = false; c.haptic(Haptic.CONFIRM) }
        else { host.delete(id); c.showToast("Auf dieser Seite ist nicht genug Platz") }
    }
    fun configure() {
        if (info.configure != null) {
            c.bridge?.configureWidget(id) { ok -> if (ok) place() else host.delete(id) } ?: host.delete(id)
        } else place()
    }
    if (host.bindIfAllowed(id, info)) {
        configure()
    } else {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        c.bridge?.startForResult(intent) { code, _ ->
            if (code == Activity.RESULT_OK) configure() else { host.delete(id); c.showToast("Widget wurde nicht erlaubt") }
        } ?: host.delete(id)
    }
}

// ============================================================================ widget editor

@Composable
fun WidgetEditorSheet(c: LauncherController, backdrop: Backdrop) {
    val id = pendingWidgetEdit
    var lastId by remember { mutableStateOf<Long?>(null) }
    if (id != null) lastId = id
    val layout by c.graph.layout.state.collectAsState()
    val item = lastId?.let { LayoutOps.findItem(layout, it) } as? WidgetItem
    GlassSheet(id != null, { pendingWidgetEdit = null }, backdrop, title = item?.kind?.title?.let { "$it bearbeiten" } ?: "Widget") {
        if (item == null) return@GlassSheet
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            when (item.kind) {
                WidgetKind.CLOCK -> ClockEditor(c)
                WidgetKind.WEATHER -> WeatherEditor(c, item)
                WidgetKind.SHORTCUTS -> ShortcutEditor(c, item)
                else -> SectionFooter("Für dieses Widget gibt es keine Optionen.")
            }
        }
    }
}

@Composable
private fun ClockEditor(c: LauncherController) {
    val cs = Lumen.settings.clock
    fun upd(f: (de.lumen.launcher.data.settings.ClockSettings) -> de.lumen.launcher.data.settings.ClockSettings) =
        c.graph.settings.update { it.copy(clock = f(it.clock)) }
    SectionHeader("Stil")
    GlassSegmented(listOf("Digital", "Analog"), cs.style.ordinal, { i -> upd { it.copy(style = ClockStyle.entries[i]) } })
    SectionHeader("Schriftstärke")
    GlassSegmented(listOf("Dünn", "Normal", "Halbfett", "Fett"), cs.weight, { i -> upd { it.copy(weight = i) } })
    SectionHeader("Anzeige")
    GlassGroup {
        GlassRow("24-Stunden-Format", subtitle = if (cs.use24h == null) "Wie System" else null, trailing = {
            GlassSwitch(cs.use24h ?: android.text.format.DateFormat.is24HourFormat(LocalContext.current), { v -> upd { it.copy(use24h = v) } })
        })
        GlassRow("Sekunden anzeigen", trailing = { GlassSwitch(cs.showSeconds, { v -> upd { it.copy(showSeconds = v) } }) })
        GlassRow("Datum anzeigen", showDivider = false, trailing = { GlassSwitch(cs.showDate, { v -> upd { it.copy(showDate = v) } }) })
    }
    SectionFooter("Gilt für alle Uhr-Widgets.")
}

@Composable
private fun WeatherEditor(c: LauncherController, item: WidgetItem) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf(item.config["city"] ?: "") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2 || query == item.config["city"]) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        loading = true
        results = ctx.graph.weather.searchPlaces(query)
        loading = false
    }
    SectionHeader("Ort")
    GlassSearchField(query, { query = it }, "Stadt suchen")
    SectionFooter("Wetterdaten von Open-Meteo. Nur der gesuchte Ort wird übertragen, kein Standortzugriff.")
    Spacer(Modifier.height(10.dp))
    if (loading) SectionFooter("Suche …")
    if (results.isNotEmpty()) {
        GlassGroup {
            results.forEachIndexed { i, p ->
                GlassRow(p.name, subtitle = p.region, icon = Icons.Rounded.LocationOn, iconTint = Color(0xFF0A84FF), showDivider = i < results.lastIndex, onClick = {
                    c.updateLayout { LayoutOps.updateWidgetConfig(it, item.id, mapOf("city" to p.name, "lat" to p.lat.toString(), "lon" to p.lon.toString())) }
                    ctx.graph.weather.refresh(p.lat, p.lon, force = true)
                    pendingWidgetEdit = null
                })
            }
        }
    }
}

@Composable
private fun ShortcutEditor(c: LauncherController, item: WidgetItem) {
    val apps = LocalApps.current.values.sortedBy { it.label.lowercase() }
    val max = when (item.size) { WidgetSize.SMALL -> 4; WidgetSize.MEDIUM -> 8; WidgetSize.LARGE -> 16 }
    val selected = item.config["apps"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    SectionFooter("Bis zu $max Apps wählen. Ohne Auswahl zeigt das Widget deine meistgenutzten Apps.")
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.heightIn(max = 460.dp).glass(SmoothRoundedShape(20.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTintThick, elevation = 0f))) {
        items(apps, key = { it.key.value }) { a ->
            val on = a.key.value in selected
            Row(
                Modifier.fillMaxWidth().pointerInput(on, selected) {
                    detectTapGestures {
                        val next = if (on) selected - a.key.value else if (selected.size < max) selected + a.key.value else selected
                        c.updateLayout { LayoutOps.updateWidgetConfig(it, item.id, mapOf("apps" to next.joinToString(","))) }
                    }
                }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconImageView(rememberAppIcon(a), 36.dp, sampleWallpaper = false)
                Spacer(Modifier.width(12.dp))
                BasicText(a.label, style = LumenType.body.copy(color = Lumen.colors.textPrimary), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.size(24.dp).clip(CircleShape).then(if (on) Modifier.background(Lumen.colors.accent) else Modifier.border(1.5.dp, Lumen.colors.textTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

// ============================================================================ reminders

@Composable
fun RemindersSheet(c: LauncherController, backdrop: Backdrop) {
    val store = c.graph.reminders
    val list by store.state.collectAsState()
    var text by remember { mutableStateOf("") }
    GlassSheet(remindersSheetOpen, { remindersSheetOpen = false }, backdrop, title = "Erinnerungen") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().glass(CircleShape, GlassStyle(sampleWallpaper = false, tint = Lumen.colors.fill, elevation = 0f)).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) BasicText("Neue Erinnerung", style = LumenType.body.copy(color = Lumen.colors.textSecondary))
                    BasicTextField(text, { text = it.take(120) }, singleLine = true, textStyle = LumenType.body.copy(color = Lumen.colors.textPrimary), cursorBrush = SolidColor(Lumen.colors.accent), modifier = Modifier.fillMaxWidth())
                }
                GlassCapsuleButton("Hinzufügen", prominent = true) {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        store.update { st -> st.copy(items = listOf(Reminder(st.nextId, t, created = System.currentTimeMillis())) + st.items, nextId = st.nextId + 1) }
                        text = ""
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (list.items.isEmpty()) SectionFooter("Noch keine Erinnerungen.")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list.items.sortedBy { it.done }, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .then(if (r.done) Modifier.background(Lumen.colors.warning) else Modifier.border(1.6.dp, Lumen.colors.warning, CircleShape))
                                .pointerInput(r) { detectTapGestures { store.update { st -> st.copy(items = st.items.map { if (it.id == r.id) it.copy(done = !it.done) else it }) } } },
                            contentAlignment = Alignment.Center,
                        ) { if (r.done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(12.dp))
                        BasicText(r.text, style = LumenType.body.copy(color = if (r.done) Lumen.colors.textSecondary else Lumen.colors.textPrimary), modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.Delete, "Löschen", tint = Lumen.colors.textTertiary, modifier = Modifier.size(22.dp).pointerInput(r.id) {
                            detectTapGestures { store.update { st -> st.copy(items = st.items.filterNot { it.id == r.id }) } }
                        })
                    }
                }
            }
            if (list.items.any { it.done }) {
                Spacer(Modifier.height(8.dp))
                GlassCapsuleButton("Erledigte löschen") { store.update { st -> st.copy(items = st.items.filterNot { it.done }) } }
            }
        }
    }
}
