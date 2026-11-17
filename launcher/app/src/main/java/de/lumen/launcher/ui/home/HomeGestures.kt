package de.lumen.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import de.lumen.launcher.data.settings.GestureAction
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.Overlay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private fun overlayFor(a: GestureAction): Overlay? = when (a) {
    GestureAction.APP_LIBRARY -> Overlay.LIBRARY
    GestureAction.SEARCH -> Overlay.SEARCH
    GestureAction.CONTROL_CENTER -> Overlay.CONTROL_CENTER
    GestureAction.NOTIFICATIONS -> Overlay.NOTIFICATIONS
    else -> null
}

/**
 * Vertical home screen gestures. Horizontal swipes are left to the pager.
 *
 * - swipe up → (default) App Library, follows the finger
 * - swipe up and hold → (default) launcher overview
 * - swipe down → (default) search
 * - swipe down from the upper right / upper left area → Control Center / notifications
 */
fun Modifier.homeGestures(c: LauncherController, topZoneHeightPx: () -> Float): Modifier = pointerInput(c) {
    val holdMs = 380L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (c.editMode || c.drag.active != null || c.anyOverlay || c.menu != null || c.openFolder != null) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        // ---- wait until the gesture is clearly vertical
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (ch.isConsumed || !ch.pressed) return@awaitEachGesture
            total += ch.positionChange()
            if (abs(total.x) > slop && abs(total.x) > abs(total.y)) return@awaitEachGesture
            if (abs(total.y) > slop) {
                ch.consume()
                break
            }
        }
        if (c.drag.pending != null || c.drag.active != null) return@awaitEachGesture
        val g = c.graph.settings.value.gestures
        val up = total.y < 0
        val inTopZone = down.position.y < topZoneHeightPx()
        val action = when {
            !up && inTopZone && down.position.x > size.width * 0.5f && g.topRightControlCenter -> GestureAction.CONTROL_CENTER
            !up && inTopZone && down.position.x <= size.width * 0.5f && g.topLeftNotifications -> GestureAction.NOTIFICATIONS
            up -> g.swipeUp
            else -> g.swipeDown
        }
        val overlay = overlayFor(action)
        val holdAction = if (up) g.swipeUpHold else GestureAction.NONE
        val travel = size.height * 0.36f
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var distance = abs(total.y)
        var anchor = Offset.Zero
        var lastMoveTime = down.uptimeMillis
        var held = false
        val holdMinDistance = 56.dp.toPx()

        while (true) {
            val ev = if (holdAction != GestureAction.NONE && !held) {
                withTimeoutOrNull(holdMs) { awaitPointerEvent(PointerEventPass.Initial) }
            } else awaitPointerEvent(PointerEventPass.Initial)

            if (ev == null) {
                // finger completely still
                if (distance > holdMinDistance) {
                    held = true
                    overlay?.let { c.dragOverlay(it, 0f); c.close(it) }
                    c.haptic(Haptic.LONG_PRESS)
                    c.run(holdAction)
                }
                continue
            }
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) {
                if (held) break
                val v = tracker.calculateVelocity()
                val vFrac = (if (up) -v.y else v.y) / (travel * 3f)
                if (overlay != null) {
                    c.settleOverlay(overlay, vFrac)
                } else if (distance > 48.dp.toPx() || abs(vFrac) > 0.6f) {
                    c.run(action)
                }
                ch.consume()
                break
            }
            tracker.addPosition(ch.uptimeMillis, ch.position)
            val d = ch.positionChange()
            total += d
            anchor += d
            distance = (if (up) -total.y else total.y).coerceAtLeast(0f)
            if (anchor.getDistance() > 8.dp.toPx()) {
                anchor = Offset.Zero
                lastMoveTime = ch.uptimeMillis
            } else if (!held && holdAction != GestureAction.NONE && distance > holdMinDistance && ch.uptimeMillis - lastMoveTime > holdMs) {
                held = true
                overlay?.let { c.dragOverlay(it, 0f); c.close(it) }
                c.haptic(Haptic.LONG_PRESS)
                c.run(holdAction)
            }
            if (!held && overlay != null) c.dragOverlay(overlay, distance / travel)
            ch.consume()
        }
    }
}
