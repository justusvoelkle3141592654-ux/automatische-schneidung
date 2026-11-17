package de.lumen.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.graph
import de.lumen.launcher.system.LauncherSystem
import de.lumen.launcher.system.NotificationHub
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassIconButton
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSheet
import de.lumen.launcher.ui.glass.SectionFooter
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType

/** Reorder, add and remove home screen pages. Removing a page keeps its apps in the library. */
@Composable
fun PageManagerSheet(c: LauncherController, backdrop: Backdrop) {
    val layout by c.graph.layout.state.collectAsState()
    GlassSheet(c.pageManagerOpen, { c.pageManagerOpen = false }, backdrop, title = "Seiten") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            GlassGroup {
                layout.pages.forEachIndexed { i, p ->
                    val apps = p.items.count { it is AppItem } + p.items.filterIsInstance<FolderItem>().sumOf { it.apps.size }
                    val widgets = p.items.size - p.items.count { it is AppItem || it is FolderItem }
                    GlassRow(
                        "Seite ${i + 1}",
                        subtitle = "$apps Apps · $widgets Widgets",
                        showDivider = i < layout.pages.lastIndex,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlassIconButton(Icons.Rounded.ArrowUpward, "Nach oben", size = 34.dp) { c.updateLayout { LayoutOps.movePage(it, i, i - 1) } }
                                Spacer(Modifier.width(6.dp))
                                GlassIconButton(Icons.Rounded.ArrowDownward, "Nach unten", size = 34.dp) { c.updateLayout { LayoutOps.movePage(it, i, i + 1) } }
                                if (layout.pages.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    GlassIconButton(Icons.Rounded.Delete, "Seite entfernen", size = 34.dp, active = true, activeColor = Lumen.colors.destructive) {
                                        p.items.filterIsInstance<NativeWidgetItem>().forEach { w -> c.graph.widgetHost.delete(w.appWidgetId) }
                                        c.updateLayout { LayoutOps.removePage(it, i) }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            SectionFooter("Beim Entfernen einer Seite bleiben die Apps installiert und in der App-Bibliothek erreichbar.")
            Spacer(Modifier.padding(6.dp))
            Row(Modifier.fillMaxWidth()) {
                GlassCapsuleButton("Seite hinzufügen", icon = Icons.Rounded.Add) { c.updateLayout { LayoutOps.addPage(it) } }
                Spacer(Modifier.weight(1f))
                GlassCapsuleButton("Leere entfernen") { c.updateLayout { LayoutOps.removeEmptyPages(it) } }
            }
        }
    }
}

/** First-run welcome: explains the essentials and offers to become the default home app. */
@Composable
fun OnboardingSheet(c: LauncherController, backdrop: Backdrop) {
    val ctx = LocalContext.current
    val s = Lumen.settings
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(s.onboardingDone) { visible = !s.onboardingDone }
    var isDefault by remember { mutableStateOf(LauncherSystem.isDefaultHome(ctx)) }
    fun finish() { visible = false; c.graph.settings.update { it.copy(onboardingDone = true) } }
    GlassSheet(visible, { finish() }, backdrop, title = null) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Willkommen bei Lumen", style = LumenType.title1.copy(color = Lumen.colors.textPrimary, textAlign = TextAlign.Center))
            Spacer(Modifier.padding(4.dp))
            BasicText(
                "Nach oben wischen öffnet die App-Bibliothek, nach unten die Suche. Oben rechts nach unten wischen öffnet das Kontrollzentrum, oben links die Mitteilungen. Langes Drücken ordnet den Home-Bildschirm.",
                style = LumenType.subhead.copy(color = Lumen.colors.textSecondary, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.padding(8.dp))
            GlassGroup {
                GlassRow(
                    "Als Standard-Home-App festlegen", icon = Icons.Rounded.Home, iconTint = Color(0xFF0A84FF),
                    value = if (isDefault) "Erledigt" else null, chevron = !isDefault,
                    onClick = { if (!isDefault) c.bridge?.startForResult(LauncherSystem.defaultHomeIntent(ctx)) { _, _ -> isDefault = LauncherSystem.isDefaultHome(ctx) } },
                )
                GlassRow(
                    "Mitteilungen anzeigen (optional)", icon = Icons.Rounded.Notifications, iconTint = Color(0xFFFF3B30),
                    value = if (NotificationHub.hasAccess(ctx)) "Erlaubt" else null, chevron = true, showDivider = false,
                    onClick = { ctx.graph.system.start(NotificationHub.accessIntent(ctx)) },
                )
            }
            Spacer(Modifier.padding(8.dp))
            GlassCapsuleButton("Los geht's", prominent = true) { finish() }
        }
    }
}
