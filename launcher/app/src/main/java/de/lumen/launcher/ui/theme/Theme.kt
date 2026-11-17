package de.lumen.launcher.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import de.lumen.launcher.R
import de.lumen.launcher.data.settings.LauncherSettings
import de.lumen.launcher.data.settings.ThemeMode

// ------------------------------------------------------------------ typography

val InterText = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplay = FontFamily(
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/** Type ramp following the iOS text styles (sizes in sp, tracking tuned for Inter). */
@Immutable
object LumenType {
    val largeTitle = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.6).sp, lineHeight = 41.sp)
    val title1 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp, lineHeight = 34.sp)
    val title2 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.35).sp, lineHeight = 28.sp)
    val title3 = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp, lineHeight = 25.sp)
    val headline = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.4).sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 17.sp, letterSpacing = (-0.4).sp, lineHeight = 22.sp)
    val callout = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = (-0.3).sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = (-0.2).sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = (-0.08).sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.06.sp, lineHeight = 13.sp)

    /** Home screen icon label: white with a soft shadow for legibility on any wallpaper. */
    val iconLabel = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
        letterSpacing = 0.sp, lineHeight = 14.sp, color = Color.White,
        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 4f),
    )
}

// ------------------------------------------------------------------ colors

/** Accent palette (system-style tints). */
val AccentPalette = listOf(
    "Blau" to Color(0xFF0A84FF),
    "Lila" to Color(0xFFBF5AF2),
    "Pink" to Color(0xFFFF375F),
    "Orange" to Color(0xFFFF9F0A),
    "Gelb" to Color(0xFFFFD60A),
    "Grün" to Color(0xFF30D158),
    "Türkis" to Color(0xFF40C8E0),
    "Graphit" to Color(0xFF8E8E93),
)

@Immutable
data class LumenColors(
    val isDark: Boolean,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Tint laid over blurred backdrop for regular glass. */
    val glassTint: Color,
    /** Tint for thicker glass (menus, panels with text). */
    val glassTintThick: Color,
    /** Tint for controls inside glass (toggles off-state, fields). */
    val fill: Color,
    val fillStrong: Color,
    val separator: Color,
    /** Specular rim highlight. */
    val rimHighlight: Color,
    val rimShade: Color,
    val shadow: Color,
    /** Dim applied behind overlays. */
    val scrim: Color,
    val destructive: Color,
    val success: Color,
    val warning: Color,
) {
    companion object {
        fun light(accent: Color) = LumenColors(
            isDark = false, accent = accent,
            textPrimary = Color(0xFF000000), textSecondary = Color(0x993C3C43), textTertiary = Color(0x4D3C3C43),
            glassTint = Color(0x52FFFFFF), glassTintThick = Color(0xB8F7F7FA),
            fill = Color(0x1F787880), fillStrong = Color(0x33787880),
            separator = Color(0x293C3C43),
            rimHighlight = Color(0xCCFFFFFF), rimShade = Color(0x33FFFFFF),
            shadow = Color(0x33000000), scrim = Color(0x1A000000),
            destructive = Color(0xFFFF3B30), success = Color(0xFF34C759), warning = Color(0xFFFF9500),
        )

        fun dark(accent: Color) = LumenColors(
            isDark = true, accent = accent,
            textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0x99EBEBF5), textTertiary = Color(0x4DEBEBF5),
            glassTint = Color(0x38202024), glassTintThick = Color(0xB31C1C1E),
            fill = Color(0x3D767680), fillStrong = Color(0x52767680),
            separator = Color(0x38545458),
            rimHighlight = Color(0x8CFFFFFF), rimShade = Color(0x1AFFFFFF),
            shadow = Color(0x66000000), scrim = Color(0x40000000),
            destructive = Color(0xFFFF453A), success = Color(0xFF30D158), warning = Color(0xFFFF9F0A),
        )
    }
}

// ------------------------------------------------------------------ motion

/**
 * Motion tokens. All springs scale with the user's animation speed setting; when animations are
 * disabled every spec snaps.
 */
@Immutable
class LumenMotion(val enabled: Boolean, val speed: Float) {
    private val stiffnessScale = speed * speed

    fun <T> spring(dampingRatio: Float = 0.82f, stiffness: Float = 380f, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (!enabled) snap() else androidx.compose.animation.core.spring(dampingRatio, stiffness * stiffnessScale, visibilityThreshold)

    /** Bouncy spring for icon presses, folder open and dock. */
    fun <T> bouncy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(0.62f, 420f, visibilityThreshold)

    /** Smooth spring for sheets/overlays (no overshoot). */
    fun <T> smooth(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(1f, 300f, visibilityThreshold)

    /** Snappy spring for small UI feedback. */
    fun <T> snappy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(0.9f, 900f, visibilityThreshold)

    fun <T> fade(durationMs: Int = 220): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween((durationMs / speed).toInt())

    val offsetSpring: FiniteAnimationSpec<IntOffset>
        get() = spring(0.8f, Spring.StiffnessMediumLow, IntOffset(1, 1))
}

// ------------------------------------------------------------------ locals

val LocalLumenColors = staticCompositionLocalOf { LumenColors.dark(AccentPalette[0].second) }
val LocalMotion = staticCompositionLocalOf { LumenMotion(true, 1f) }
val LocalSettings = staticCompositionLocalOf { LauncherSettings() }

object Lumen {
    val colors: LumenColors @Composable get() = LocalLumenColors.current
    val motion: LumenMotion @Composable get() = LocalMotion.current
    val settings: LauncherSettings @Composable get() = LocalSettings.current
}

@Composable
fun LumenTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = AccentPalette.getOrElse(settings.accentIndex) { AccentPalette[0] }.second
    val colors = remember(dark, accent) { if (dark) LumenColors.dark(accent) else LumenColors.light(accent) }
    val motion = remember(settings.animationsEnabled, settings.animationSpeed) {
        LumenMotion(settings.animationsEnabled, settings.animationSpeed.coerceIn(0.5f, 2f))
    }
    val scheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalLumenColors provides colors,
            LocalMotion provides motion,
            LocalSettings provides settings,
            content = content,
        )
    }
}
