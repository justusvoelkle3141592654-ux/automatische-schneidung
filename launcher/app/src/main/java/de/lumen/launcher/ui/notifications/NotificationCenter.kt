package de.lumen.launcher.ui.notifications

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.graph
import de.lumen.launcher.system.LumenLockService
import de.lumen.launcher.system.NotificationEntry
import de.lumen.launcher.system.NotificationHub
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
import de.lumen.launcher.ui.theme.InterDisplay
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.ui.widgets.rememberNow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val CardShape = SmoothRoundedShape(24.dp)

@Composable
fun NotificationCenter(c: LauncherController, backdrop: Backdrop) {
    val anim = c.anim(Overlay.NOTIFICATIONS)
    if (!anim.visible) return
    val ctx = LocalContext.current
    val items by NotificationHub.items.collectAsState()
    val connected by NotificationHub.connected.collectAsState()
    var access by remember { mutableStateOf(NotificationHub.hasAccess(ctx)) }
    LaunchedEffect(anim.target, connected) { access = NotificationHub.hasAccess(ctx) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now = rememberNow(false)
    val groups = remember(items) { items.groupBy { it.packageName to it.user.hashCode() }.values.sortedByDescending { it.first().postTime } }

    OverlayFrame(backdrop, { anim.progress.value }, blur = 58.dp, scrim = Color.Black.copy(alpha = 0.30f)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = anim.progress.value.coerceIn(0f, 1f)
                        translationY = -(1f - p) * h * 0.35f
                        alpha = p
                    }
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = { if (acc < -140f) c.close(Overlay.NOTIFICATIONS) else c.open(Overlay.NOTIFICATIONS) },
                            onVerticalDrag = { ch, dy ->
                                ch.consume(); acc = (acc + dy).coerceAtMost(0f)
                                c.dragOverlay(Overlay.NOTIFICATIONS, 1f + acc / (h * 0.5f))
                            },
                        )
                    }
                    .padding(top = statusTop + 24.dp),
            ) {
                // Lock-screen style clock header.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now),
                        style = LumenType.headline.copy(color = Color.White.copy(alpha = 0.9f)),
                    )
                    BasicText(
                        SimpleDateFormat("H:mm", Locale.GERMANY).format(now),
                        style = LumenType.largeTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 88.sp, lineHeight = 92.sp, letterSpacing = (-3).sp, color = Color.White),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Mitteilungszentrale", style = LumenType.title3.copy(color = Color.White), modifier = Modifier.weight(1f))
                    if (items.any { it.clearable }) {
                        Box(
                            Modifier.size(32.dp).glass(androidx.compose.foundation.shape.CircleShape, GlassStyle(sampleWallpaper = false, elevation = 0.4f))
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .liquidPress(shape = androidx.compose.foundation.shape.CircleShape) { NotificationHub.dismissAll() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, "Alle löschen", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    !access -> PermissionCard(c)
                    items.isEmpty() -> EmptyState(c)
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = navBottom + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(groups, key = { it.first().packageName + it.first().user.hashCode() }) { group ->
                            val gk = group.first().packageName + group.first().user.hashCode()
                            NotificationGroup(c, group, expanded[gk] == true, now, onToggle = { expanded[gk] = expanded[gk] != true })
                        }
                        if (LumenLockService.isRunning) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                                    GlassCapsuleButton("System-Mitteilungen öffnen") { LumenLockService.openSystemNotifications() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationGroup(c: LauncherController, group: List<NotificationEntry>, expanded: Boolean, now: Long, onToggle: () -> Unit) {
    val first = group.first()
    Column(Modifier.fillMaxWidth().animateContentSize(Lumen.motion.spring(0.85f, 380f))) {
        if (group.size > 1 && expanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(first.appLabel, style = LumenType.title3.copy(color = Color.White), modifier = Modifier.weight(1f))
                GlassCapsuleButton("Weniger") { onToggle() }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(32.dp).glass(androidx.compose.foundation.shape.CircleShape, GlassStyle(sampleWallpaper = false))
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .liquidPress(shape = androidx.compose.foundation.shape.CircleShape) { group.filter { it.clearable }.forEach { NotificationHub.dismiss(it.key) } },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Gruppe löschen", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.height(6.dp))
            group.forEach { e ->
                SwipeCard(c, e, now, Modifier.padding(bottom = 8.dp))
            }
        } else {
            Box {
                // Stacked layers hint at more notifications from the same app.
                if (group.size > 2) StackLayer(2)
                if (group.size > 1) StackLayer(1)
                SwipeCard(c, first, now, extra = if (group.size > 1) "+${group.size - 1} weitere" else null, onTapOverride = if (group.size > 1) onToggle else null)
            }
        }
    }
}

@Composable
private fun StackLayer(depth: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = (12 * depth).dp)
            .offset(y = (9 * depth).dp)
            .height(90.dp)
            .graphicsLayer { alpha = 1f - 0.25f * depth }
            .glass(CardShape, GlassStyle(sampleWallpaper = false, elevation = 0.3f))
    )
}

@Composable
private fun SwipeCard(
    c: LauncherController,
    e: NotificationEntry,
    now: Long,
    modifier: Modifier = Modifier,
    extra: String? = null,
    onTapOverride: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val apps = LocalApps.current
    val offset = remember(e.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val app = remember(apps, e.packageName) { apps.values.firstOrNull { it.packageName == e.packageName && it.user == e.user } ?: apps.values.firstOrNull { it.packageName == e.packageName } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth.toFloat()
        Row(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(offset.value) / w).coerceIn(0f, 1f) * 0.8f }
                .fillMaxWidth()
                .glass(CardShape, GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTintThick.copy(alpha = 0.55f), elevation = 0.6f))
                .clip(CardShape)
                .pointerInput(e.key) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x
                            val gone = e.clearable && (abs(offset.value) > w * 0.38f || abs(v) > 2200f)
                            scope.launch {
                                if (gone) {
                                    offset.animateTo(if (offset.value + v * 0.05f < 0) -w * 1.2f else w * 1.2f)
                                    NotificationHub.dismiss(e.key)
                                } else offset.animateTo(0f, androidx.compose.animation.core.spring(0.7f, 500f))
                            }
                        },
                        onHorizontalDrag = { ch, dx ->
                            ch.consume()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val damp = if (e.clearable) 1f else 0.25f
                            scope.launch { offset.snapTo(offset.value + dx * damp) }
                        },
                    )
                }
                .liquidPress(pressScale = 0.98f, shape = CardShape) {
                    if (onTapOverride != null) onTapOverride()
                    else if (NotificationHub.open(ctx, e)) c.closeAll()
                    else app?.let { c.launch(it, null) }
                }
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (app != null) IconImageView(rememberAppIcon(app), 38.dp, sampleWallpaper = false)
            else Box(Modifier.size(38.dp).clip(SmoothRoundedShape(9.dp)).background(Color(e.color or 0xFF000000.toInt()).copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                e.smallIcon?.let { Image(it, null, Modifier.size(22.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        e.title.ifBlank { e.appLabel },
                        style = LumenType.subhead.copy(color = Lumen.colors.textPrimary, fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(RelativeTime.format(e.postTime, now), style = LumenType.footnote.copy(color = Lumen.colors.textSecondary))
                }
                if (e.text.isNotBlank()) {
                    BasicText(e.text, style = LumenType.subhead.copy(color = Lumen.colors.textPrimary), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (extra != null) {
                    Spacer(Modifier.height(2.dp))
                    BasicText(extra, style = LumenType.footnote.copy(color = Lumen.colors.textSecondary))
                }
            }
            e.largeIcon?.let {
                Spacer(Modifier.width(10.dp))
                Image(it, null, Modifier.size(42.dp).clip(SmoothRoundedShape(8.dp)))
            }
        }
    }
}

@Composable
private fun PermissionCard(c: LauncherController) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .glass(SmoothRoundedShape(26.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTintThick, elevation = 0.8f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.NotificationsActive, null, tint = Lumen.colors.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        BasicText("Mitteilungen anzeigen", style = LumenType.title3.copy(color = Lumen.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Damit Lumen deine Mitteilungen hier gruppiert anzeigen, öffnen und entfernen kann, braucht es den Android-Zugriff „Mitteilungszugriff“. " +
                "Die Inhalte bleiben auf dem Gerät und werden weder gespeichert noch übertragen. Der Zugriff ermöglicht auch die Mediensteuerung.",
            style = LumenType.subhead.copy(color = Lumen.colors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        Spacer(Modifier.height(14.dp))
        GlassCapsuleButton("Zugriff erlauben", prominent = true) { ctx.graph.system.start(NotificationHub.accessIntent(ctx)) }
    }
}

@Composable
private fun EmptyState(c: LauncherController) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.NotificationsNone, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        BasicText("Keine Mitteilungen", style = LumenType.headline.copy(color = Color.White.copy(alpha = 0.8f)))
    }
}
