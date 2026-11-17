package de.lumen.launcher.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.theme.LumenType

/** Dispatches a built-in widget to its implementation. */
@Composable
fun BuiltinWidget(c: LauncherController, item: WidgetItem) {
    when (item.kind) {
        WidgetKind.CLOCK -> ClockWidget(item)
        WidgetKind.DATE -> DateWidget(item)
        else -> Box(Modifier.fillMaxSize().padding(16.dp)) {
            BasicText(item.kind.title, style = LumenType.headline.copy(color = Color.White))
        }
    }
}

@Composable
fun NativeWidget(c: LauncherController, item: NativeWidgetItem) {
}
