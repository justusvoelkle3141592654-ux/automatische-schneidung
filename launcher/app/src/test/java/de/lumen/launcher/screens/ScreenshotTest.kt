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
import de.lumen.launcher.data.layout.WidgetItem as W
import de.lumen.launcher.data.layout.Cell as C
import de.lumen.launcher.data.layout.WidgetKind as K
import de.lumen.launcher.data.layout.WidgetSize as S
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

    @Test fun widgets_page() {
        setUp(block = { c ->
            val g = c.graph
            g.weather.putForTest(48.14, 11.58, de.lumen.launcher.widgets.WeatherRepository.parseForecast(WEATHER_SAMPLE, System.currentTimeMillis()))
            g.reminders.set(de.lumen.launcher.widgets.ReminderList(listOf(
                de.lumen.launcher.widgets.Reminder(1, "Präsentation vorbereiten"), de.lumen.launcher.widgets.Reminder(2, "Milch kaufen"), de.lumen.launcher.widgets.Reminder(3, "Paket abholen")), 4))
            g.layout.update { l ->
                l.copy(pages = listOf(de.lumen.launcher.data.layout.HomePage(900, listOf(
                    W(901, C(0, 0, 4, 2), K.WEATHER, S.MEDIUM, mapOf("city" to "München", "lat" to "48.14", "lon" to "11.58")),
                    W(902, C(0, 2, 2, 2), K.BATTERY, S.SMALL),
                    W(903, C(2, 2, 2, 2), K.REMINDERS, S.SMALL),
                    W(904, C(0, 4, 4, 2), K.SHORTCUTS, S.MEDIUM),
                ))))
            }
        })
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage("$out/widgets.png")
    }

    @Test fun widget_gallery() {
        setUp(block = { c -> c.editMode = true; c.widgetPickerOpen = true })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/widget_gallery.png")
    }

    @Test fun settings_root() {
        setUp({ it.copy(themeMode = de.lumen.launcher.data.settings.ThemeMode.DARK) }, block = { c -> c.settingsOpen = true })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/settings.png")
    }

    @Test fun overview() {
        setUp(block = { c -> listOf(9, 6, 4).forEach { c.recordLaunch(TestFixtures.key(it)) }; c.open(de.lumen.launcher.ui.Overlay.OVERVIEW) })
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/overview.png")
    }

    companion object {
        const val WEATHER_SAMPLE = """{"latitude":48.12743,"longitude":11.576928,"current":{"time":"2026-11-17T18:15","interval":900,"temperature_2m":12.7,"weather_code":0,"is_day":0},"hourly":{"time":["2026-11-17T18:00","2026-11-17T19:00","2026-11-17T20:00","2026-11-17T21:00","2026-11-17T22:00","2026-11-17T23:00"],"temperature_2m":[12.8,12.5,11.9,11.0,10.3,10.5],"weather_code":[0,0,1,1,0,3]},"daily":{"time":["2026-11-17","2026-11-18","2026-11-19","2026-11-20","2026-11-21"],"weather_code":[61,80,80,80,80],"temperature_2m_max":[13.9,12.0,9.1,9.2,7.6],"temperature_2m_min":[6.5,7.8,6.2,6.6,1.9]}}"""
    }
}
