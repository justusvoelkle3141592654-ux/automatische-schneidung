package de.lumen.launcher.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.lumen.launcher.LumenApp
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.LauncherRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = LumenApp::class)
class LandscapeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun home_landscape() {
        val graph = ApplicationProvider.getApplicationContext<LumenApp>().graph
        TestFixtures.install(graph)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val c = remember { LauncherController(graph, scope).also { it.isLandscape = true } }
            LauncherRoot(c) {}
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_landscape.png")
    }
}
