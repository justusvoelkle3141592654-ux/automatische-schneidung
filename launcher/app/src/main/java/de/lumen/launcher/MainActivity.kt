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
import android.media.projection.MediaProjectionManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import de.lumen.launcher.system.LumenLockService
import de.lumen.launcher.system.ScreenRecordService
import de.lumen.launcher.ui.ActivityBridge
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.LauncherRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), ActivityBridge {

    private var controller: LauncherController? = null

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var imageCallback: ((android.net.Uri?) -> Unit)? = null
    private var resultCallback: ((Int, Intent?) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionCallback?.invoke(ok); permissionCallback = null
    }
    private val imageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        imageCallback?.invoke(uri); imageCallback = null
    }
    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        resultCallback?.invoke(r.resultCode, r.data); resultCallback = null
    }
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) ScreenRecordService.start(this, r.resultCode, data)
        else controller?.showToast("Aufnahme abgebrochen")
    }

    override fun startScreenRecording() {
        if (ScreenRecordService.recording.value) { ScreenRecordService.stop(this); return }
        val go = {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // The recording notification (with the stop button) needs this permission.
            requestPermission(android.Manifest.permission.POST_NOTIFICATIONS) { go() }
        } else go()
    }

    override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) { onResult(true); return }
        permissionCallback = onResult
        permissionLauncher.launch(permission)
    }

    override fun pickImage(onResult: (android.net.Uri?) -> Unit) {
        imageCallback = onResult
        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private var configureCallback: ((Boolean) -> Unit)? = null

    override fun configureWidget(appWidgetId: Int, onResult: (Boolean) -> Unit) {
        configureCallback = onResult
        try {
            graph.widgetHost.host.startAppWidgetConfigureActivityForResult(this, appWidgetId, 0, REQ_CONFIGURE, null)
        } catch (t: Throwable) {
            configureCallback = null
            onResult(false)
        }
    }

    @Deprecated("AppWidgetHost delivers the configuration result through this callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            configureCallback?.invoke(resultCode == RESULT_OK)
            configureCallback = null
        }
    }

    override fun startForResult(intent: Intent, onResult: (Int, Intent?) -> Unit) {
        resultCallback = onResult
        try { resultLauncher.launch(intent) } catch (t: Throwable) { resultCallback = null; onResult(RESULT_CANCELED, null) }
    }

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
            c.bridge = this
            c.lockScreen = { LumenLockService.lock() }
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
        graph.system.refresh()
    }

    override fun onStart() {
        super.onStart()
        graph.widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        graph.widgetHost.stopListening()
        controller?.cancelDrag()
        graph.flushAll()
    }

    companion object {
        private const val REQ_CONFIGURE = 4711
    }
}
