package de.lumen.launcher.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.lumen.launcher.LumenApp
import de.lumen.launcher.data.settings.LauncherSettings
import de.lumen.launcher.data.settings.ThemeMode
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassIconButton
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSearchField
import de.lumen.launcher.ui.glass.GlassSegmented
import de.lumen.launcher.ui.glass.GlassSlider
import de.lumen.launcher.ui.glass.GlassSwitch
import de.lumen.launcher.ui.glass.GlassVerticalSlider
import de.lumen.launcher.ui.glass.SectionHeader
import de.lumen.launcher.ui.theme.LumenTheme
import de.lumen.launcher.ui.wallpaper.LocalWallpaper
import de.lumen.launcher.ui.wallpaper.rememberWallpaperState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = LumenApp::class)
class DesignSystemTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showcase(theme: ThemeMode, file: String) {
        val settings = LauncherSettings(themeMode = theme, wallpaperPreset = if (theme == ThemeMode.LIGHT) 3 else 0)
        compose.setContent {
            LumenTheme(settings) {
                val d = LocalDensity.current
                val screen = with(d) { IntSize(411.dp.roundToPx(), 891.dp.roundToPx()) }
                val wp = rememberWallpaperState(settings, screen, theme == ThemeMode.DARK)
                CompositionLocalProvider(LocalWallpaper provides wp) {
                    Box(Modifier.fillMaxSize()) {
                        wp.image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Column(Modifier.padding(16.dp).padding(top = 24.dp)) {
                            GlassSearchField("", {}, "Apps und Web durchsuchen")
                            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                GlassIconButton(Icons.Rounded.Wifi, null, size = 60.dp, active = true, sampleWallpaper = true) {}
                                GlassIconButton(Icons.Rounded.Bluetooth, null, size = 60.dp, sampleWallpaper = true) {}
                                GlassVerticalSlider(0.6f, {}, Icons.Rounded.LightMode, Modifier.width(70.dp).height(150.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GlassCapsuleButton("Fertig") {}
                                    GlassCapsuleButton("Erlauben", prominent = true) {}
                                    GlassSwitch(true, {})
                                }
                            }
                            GlassSegmented(listOf("Automatisch", "Hell", "Dunkel"), 2, {}, Modifier.padding(top = 18.dp))
                            SectionHeader("Darstellung")
                            GlassGroup {
                                GlassRow("Akzentfarbe", icon = Icons.Rounded.Palette, value = "Blau", chevron = true, onClick = {})
                                GlassRow("Animationen", subtitle = "Federnde Übergänge", trailing = { GlassSwitch(true, {}) })
                                Box(Modifier.fillMaxWidth().padding(16.dp)) { GlassSlider(0.4f, {}) }
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(1200)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test fun components_dark() = showcase(ThemeMode.DARK, "design_dark.png")
    @Test fun components_light() = showcase(ThemeMode.LIGHT, "design_light.png")

    @Suppress("unused")
    private fun unused() = LocalContext
}
