package de.lumen.launcher.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.lumen.launcher.LumenApp
import de.lumen.launcher.data.settings.LauncherSettings
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.LauncherRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real launcher screens on the JVM (Robolectric native graphics) and stores PNGs in
 * app/build/outputs/roborazzi. Used for visual QA without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = LumenApp::class)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = "build/outputs/roborazzi"

    private fun setUp(settings: (LauncherSettings) -> LauncherSettings = { it }, block: (LauncherController) -> Unit = {}): LauncherController {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val graph = app.graph
        TestFixtures.install(graph)
        graph.settings.update(settings)
        lateinit var controller: LauncherController
        compose.setContent {
            val scope = rememberCoroutineScope()
            val c = remember { LauncherController(graph, scope) }
            controller = c
            LauncherRoot(c) {}
        }
        compose.waitUntil(10_000) { true }
        // Let the wallpaper render on the background dispatcher.
        Thread.sleep(1500)
        compose.waitForIdle()
        block(controller)
        compose.waitForIdle()
        return controller
    }

    @Test fun home_dark() {
        setUp({ it.copy(themeMode = de.lumen.launcher.data.settings.ThemeMode.DARK) })
        compose.onRoot().captureRoboImage("$out/home_dark.png")
    }

    @Test fun home_light_wallpaper() {
        setUp({ it.copy(themeMode = de.lumen.launcher.data.settings.ThemeMode.LIGHT, wallpaperPreset = 3) })
        compose.onRoot().captureRoboImage("$out/home_light.png")
    }

    @Test fun home_edit_mode() {
        setUp(block = { it.editMode = true })
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("$out/home_edit.png")
    }

    @Test fun folder_open() {
        setUp(block = { c ->
            val folder = c.graph.layout.value.pages[0].items.first { it is de.lumen.launcher.data.layout.FolderItem }
            c.openFolder = de.lumen.launcher.ui.FolderOpen(folder.id, androidx.compose.ui.geometry.Rect(560f, 760f, 740f, 940f))
        })
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("$out/folder_open.png")
    }

    @Test fun context_menu() {
        setUp(block = { c ->
            val apps = c.graph.apps.apps.value
            val item = c.graph.layout.value.pages[0].items.first { it is de.lumen.launcher.data.layout.AppItem } as de.lumen.launcher.data.layout.AppItem
            val app = apps.first { it.key == item.app }
            c.menu = de.lumen.launcher.ui.MenuState(de.lumen.launcher.ui.MenuTarget.App(app, item), androidx.compose.ui.geometry.Rect(820f, 760f, 1000f, 940f))
        })
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("$out/context_menu.png")
    }

    @Test fun library() {
        setUp(block = { c -> c.open(de.lumen.launcher.ui.Overlay.LIBRARY) })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/library.png")
    }

    @Test fun search_query() {
        setUp(block = { c ->
            c.recordLaunch(TestFixtures.key(9)); c.recordLaunch(TestFixtures.key(6))
            c.open(de.lumen.launcher.ui.Overlay.SEARCH)
        })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/search.png")
    }

    @Test fun control_center() {
        setUp(block = { c -> c.open(de.lumen.launcher.ui.Overlay.CONTROL_CENTER) })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/control_center.png")
    }

    @Test fun notifications_permission() {
        setUp(block = { c -> c.open(de.lumen.launcher.ui.Overlay.NOTIFICATIONS) })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/notifications.png")
    }
}
