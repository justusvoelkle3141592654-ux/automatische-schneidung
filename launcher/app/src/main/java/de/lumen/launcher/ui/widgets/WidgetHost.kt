package de.lumen.launcher.ui.widgets

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.HomeItem
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.PendingDrag
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.home.HomeDimens
import de.lumen.launcher.ui.home.RemoveBadge
import de.lumen.launcher.ui.home.jiggle
import de.lumen.launcher.ui.home.pressFeedback

/**
 * Hosts one home screen widget: glass card, press feedback, long-press menu, drag & drop and
 * the remove badge in edit mode.
 */
@Composable
fun WidgetHost(c: LauncherController, item: HomeItem, hidden: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val span = item.cell.w to item.cell.h
    fun pending(down: Offset) = coords?.takeIf { it.isAttached }?.let {
        PendingDrag(DragPayload.Existing(item.id), DragVisual.Widget(item), it.boundsInRoot(), down, item.id, span)
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .jiggle(c.editMode, item.id)
            .pressFeedback(pressed, 0.96f)
            .onGloballyPositioned { coords = it }
            .pointerInput(c.editMode, item) {
                detectTapGestures(
                    onPress = { pos ->
                        pressed = true
                        if (c.editMode) coords?.takeIf { it.isAttached }?.let { co -> pending(co.positionInRoot() + pos)?.let(c::beginPendingDrag) }
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (!c.editMode && item is WidgetItem) onWidgetTap(ctx, item) },
                    onLongPress = { pos ->
                        pressed = false
                        val co = coords?.takeIf { it.isAttached } ?: return@detectTapGestures
                        c.haptic(Haptic.LONG_PRESS)
                        c.menu = MenuState(MenuTarget.Widget(item), co.boundsInRoot())
                        pending(co.positionInRoot() + pos)?.let(c::beginPendingDrag)
                    },
                )
            },
    ) {
        when (item) {
            is WidgetItem -> Box(
                Modifier
                    .fillMaxSize()
                    .glass(SmoothRoundedShape(HomeDimens.widgetCorner), GlassStyle(elevation = 0.8f))
                    .clip(SmoothRoundedShape(HomeDimens.widgetCorner)),
            ) {
                BuiltinWidget(c, item)
            }
            is NativeWidgetItem -> NativeWidget(c, item)
            else -> Unit
        }
        if (c.editMode) {
            RemoveBadge({ c.removeFromHome(item.id) }, Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp))
        }
    }
}

/** Default tap targets of built-in widgets (official intents only). */
fun onWidgetTap(ctx: Context, item: WidgetItem) {
    val intent = when (item.kind) {
        WidgetKind.CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        WidgetKind.DATE, WidgetKind.CALENDAR -> Intent(Intent.ACTION_VIEW).setData(
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
        )
        WidgetKind.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        else -> null
    } ?: return
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}
