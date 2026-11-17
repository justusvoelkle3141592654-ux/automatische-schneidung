package de.lumen.launcher

import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.IconSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val g = graph
        setContent {
            val apps by g.apps.apps.collectAsState()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B2A6B), Color(0xFF6B2A7B))))
                    .systemBarsPadding()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    items(apps, key = { it.key.value }) { app -> SimpleIcon(app) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        graph.flushAll()
    }
}

@Composable
private fun SimpleIcon(app: AppInfo) {
    val ctx = LocalView.current.context
    val view = LocalView.current
    val px = with(LocalDensity.current) { 60.dp.roundToPx() }
    val spec = remember(px) { IconSpec(px) }
    val icon by produceState(ctx.graph.icons.peek(app.key, spec), app.key) {
        value = ctx.graph.icons.load(app, spec)
    }
    var bounds = remember { Rect() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned {
                val b = it.boundsInWindow()
                bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            }
            .clickable { ctx.graph.apps.launch(app, view, bounds) },
    ) {
        icon?.let { Image(it.bitmap, null, Modifier.size(60.dp)) } ?: Spacer(Modifier.size(60.dp))
        Spacer(Modifier.height(6.dp))
        BasicText(
            app.label,
            style = TextStyle(color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
