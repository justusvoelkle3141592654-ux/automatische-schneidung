package de.lumen.launcher.ui.search

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lumen.launcher.data.apps.Calculator
import de.lumen.launcher.data.apps.LibraryModel
import de.lumen.launcher.data.apps.SearchEngine
import de.lumen.launcher.data.apps.UsageRanking
import de.lumen.launcher.data.apps.normalizeForSearch
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.Overlay
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSearchField
import de.lumen.launcher.ui.glass.OverlayFrame
import de.lumen.launcher.ui.glass.SectionHeader
import de.lumen.launcher.ui.library.AppRow
import de.lumen.launcher.ui.library.LibraryIcon
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.flow.first

private data class LauncherAction(val title: String, val keywords: List<String>, val icon: ImageVector, val color: Color, val run: (LauncherController) -> Unit)

private val launcherActions = listOf(
    LauncherAction("Launcher-Einstellungen", listOf("einstellungen", "settings", "lumen", "optionen"), Icons.Rounded.Settings, Color(0xFF8E8E93)) { it.closeAll(); it.settingsOpen = true },
    LauncherAction("Kontrollzentrum", listOf("kontrollzentrum", "wlan", "bluetooth", "taschenlampe", "helligkeit", "control"), Icons.Rounded.ControlCamera, Color(0xFF0A84FF)) { it.open(Overlay.CONTROL_CENTER) },
    LauncherAction("Mitteilungen", listOf("mitteilungen", "benachrichtigungen", "notifications"), Icons.Rounded.Notifications, Color(0xFFFF3B30)) { it.open(Overlay.NOTIFICATIONS) },
    LauncherAction("App-Bibliothek", listOf("bibliothek", "apps", "library", "alle apps"), Icons.Rounded.Apps, Color(0xFF5E5CE6)) { it.open(Overlay.LIBRARY) },
    LauncherAction("Home-Bildschirm bearbeiten", listOf("bearbeiten", "anordnen", "widgets", "home"), Icons.Rounded.ViewCarousel, Color(0xFF30D158)) { it.closeAll(); it.editMode = true },
    LauncherAction("Hintergrundbild ändern", listOf("hintergrund", "wallpaper", "hintergrundbild"), Icons.Rounded.Wallpaper, Color(0xFFFF9F0A)) { it.closeAll(); it.settingsOpen = true },
)

@Composable
fun SearchOverlay(c: LauncherController, backdrop: Backdrop) {
    val anim = c.anim(Overlay.SEARCH)
    if (!anim.visible) return
    val ctx = LocalContext.current
    val apps by c.graph.apps.apps.collectAsState()
    val lib by c.graph.library.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val now = remember(anim.target) { System.currentTimeMillis() }
    val visible = remember(apps, lib) { LibraryModel.visibleApps(apps, lib) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(anim.target) {
        if (anim.target == 1f) {
            // Focus once the overlay has settled so the keyboard slides in with it.
            snapshotFlow { anim.progress.value }.first { it > 0.9f }
            runCatching { requester.requestFocus() }
            keyboard?.show()
        } else if (anim.target == 0f) {
            query = ""
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    OverlayFrame(backdrop, { anim.progress.value }, blur = 52.dp) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { c.close(Overlay.SEARCH) } })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = anim.progress.value.coerceIn(0f, 1f)
                        translationY = -(1f - p) * h * 0.08f
                        alpha = p
                    }
                    .imePadding()
                    .padding(bottom = navBottom + 10.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState(), reverseScrolling = false)
                        .padding(horizontal = 16.dp)
                        .padding(top = statusTop + 8.dp),
                ) {
                    val q = query.trim()
                    if (q.isEmpty()) {
                        Suggestions(c, visible, lib.usage, now)
                    } else {
                        Results(c, ctx, q, SearchEngine.search(visible, q, lib.usage, now, 12))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                GlassSearchField(
                    query, { query = it }, "Suchen",
                    Modifier.padding(horizontal = 16.dp),
                    focusRequester = requester,
                    onDone = {
                        val first = SearchEngine.search(visible, query, lib.usage, now, 1).firstOrNull()
                        if (first != null) c.launch(first, null) else if (query.isNotBlank()) webSearch(ctx, query)
                    },
                )
            }
        }
    }
}

@Composable
private fun Suggestions(c: LauncherController, apps: List<de.lumen.launcher.data.apps.AppInfo>, usage: Map<String, de.lumen.launcher.data.apps.UsageEntry>, now: Long) {
    val byKey = apps.associateBy { it.key.value }
    val used = UsageRanking.suggestions(usage, now, 8).mapNotNull { byKey[it] }
    val suggested = (used + apps.filter { it !in used }).take(8)
    val recents = UsageRanking.recents(usage, 4).mapNotNull { byKey[it] }
    SectionHeader("Vorschläge")
    GlassGroup {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            suggested.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { a -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LibraryIcon(c, a, 56.dp, label = true, onGlass = true) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (recents.isNotEmpty()) {
        SectionHeader("Zuletzt verwendet")
        GlassGroup {
            Column(Modifier.padding(horizontal = 14.dp)) { recents.forEach { AppRow(c, it) } }
        }
    }
}

@Composable
private fun Results(c: LauncherController, ctx: Context, q: String, apps: List<de.lumen.launcher.data.apps.AppInfo>) {
    val colors = Lumen.colors
    Calculator.evaluate(q)?.let { v ->
        SectionHeader("Rechner")
        GlassGroup {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(q, style = LumenType.subhead.copy(color = colors.textSecondary))
                    BasicText("= ${Calculator.format(v)}", style = LumenType.title1.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 30.sp))
                }
                androidx.compose.material3.Icon(Icons.Rounded.Calculate, null, tint = colors.accent)
            }
        }
    }
    if (apps.isNotEmpty()) {
        SectionHeader("Toptreffer")
        GlassGroup {
            Column(Modifier.padding(horizontal = 14.dp)) { apps.take(1).forEach { AppRow(c, it) } }
        }
        if (apps.size > 1) {
            SectionHeader("Apps")
            GlassGroup {
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    apps.drop(1).take(8).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { a -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LibraryIcon(c, a, 56.dp, label = true, onGlass = true) } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
    val nq = normalizeForSearch(q)
    val actions = launcherActions.filter { a -> normalizeForSearch(a.title).contains(nq) || a.keywords.any { it.startsWith(nq) || nq.length >= 3 && it.contains(nq) } }
    if (actions.isNotEmpty()) {
        SectionHeader("Lumen")
        GlassGroup {
            actions.forEachIndexed { i, a ->
                GlassRow(a.title, icon = a.icon, iconTint = a.color, showDivider = i < actions.lastIndex, chevron = true, onClick = { a.run(c) })
            }
        }
    }
    SectionHeader("Weitere Suche")
    GlassGroup {
        GlassRow("„$q“ im Web suchen", icon = Icons.Rounded.Public, iconTint = Color(0xFF0A84FF), chevron = true, onClick = { webSearch(ctx, q) })
        GlassRow("„$q“ im App-Store suchen", icon = Icons.Rounded.Shop, iconTint = Color(0xFF30D158), showDivider = false, chevron = true, onClick = { storeSearch(ctx, q) })
    }
}

/** Uses the system web search intent; falls back to a browser URL if no handler exists. */
fun webSearch(ctx: Context, q: String) {
    val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (_: Throwable) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }
}

fun storeSearch(ctx: Context, q: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        webSearch(ctx, "$q App")
    }
}
