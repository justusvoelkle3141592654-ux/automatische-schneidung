package de.lumen.launcher.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import de.lumen.launcher.ui.home.pressFeedback
import de.lumen.launcher.ui.theme.Lumen
import kotlinx.coroutines.launch

/**
 * Animates layout position changes with a spring (items gliding to their new grid cell).
 */
@Composable
fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    val spec = Lumen.motion.offsetSpring
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val anim = remember { arrayOfNulls<Animatable<IntOffset, *>>(1) }
    return this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val t = target ?: return@offset IntOffset.Zero
            @Suppress("UNCHECKED_CAST")
            val a = (anim[0] as Animatable<IntOffset, *>?) ?: Animatable(t, IntOffset.VectorConverter).also { anim[0] = it }
            if (a.targetValue != t) scope.launch { a.animateTo(t, spec) }
            a.value - t
        }
}

/** Tap handler with springy press feedback (no ripple – iOS style). */
@Composable
fun Modifier.pressable(enabled: Boolean = true, scale: Float = 0.94f, onClick: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    return this
        .pressFeedback(pressed, scale)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
            )
        }
}
