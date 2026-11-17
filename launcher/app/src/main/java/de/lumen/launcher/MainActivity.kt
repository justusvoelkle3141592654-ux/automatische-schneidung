package de.lumen.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.LauncherRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var controller: LauncherController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        val g = graph
        applyOrientation(g.settings.value.allowRotation)
        lifecycleScope.launch {
            g.settings.state.collect { applyOrientation(it.allowRotation) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller?.onBack()
            }
        })

        setContent {
            val scope = rememberCoroutineScope()
            val view = LocalView.current
            val c = remember { LauncherController(g, scope) }
            controller = c
            c.view = view
            c.isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            c.haptic = { type ->
                if (g.settings.value.gestures.haptics) {
                    view.performHapticFeedback(
                        when (type) {
                            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
                            Haptic.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
                            Haptic.CONFIRM -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                        }
                    )
                }
            }
            LauncherRoot(c) { light ->
                insets.isAppearanceLightStatusBars = light
                insets.isAppearanceLightNavigationBars = light
            }
        }
    }

    private fun applyOrientation(allow: Boolean) {
        val want = if (allow) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while the launcher is in front.
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val alreadyOnTop = (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
            if (alreadyOnTop) controller?.onHomePressed()
        }
    }

    override fun onResume() {
        super.onResume()
        controller?.onReturnHome()
    }

    override fun onStop() {
        super.onStop()
        controller?.cancelDrag()
        graph.flushAll()
    }
}
