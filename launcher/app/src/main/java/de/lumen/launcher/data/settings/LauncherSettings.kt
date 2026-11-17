package de.lumen.launcher.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class WallpaperMode { PRESET, CUSTOM, SYSTEM }

/** Icon appearance, modelled after the iOS 26 home screen icon styles. */
@Serializable
enum class IconStyle { DEFAULT, DARK, TINTED, CLEAR }

@Serializable
enum class ClockStyle { DIGITAL, ANALOG }

/** Actions that can be bound to home screen gestures. */
@Serializable
enum class GestureAction(val title: String) {
    NONE("Keine Aktion"),
    APP_LIBRARY("App-Bibliothek"),
    SEARCH("Suche"),
    NOTIFICATIONS("Mitteilungen"),
    CONTROL_CENTER("Kontrollzentrum"),
    OVERVIEW("Übersicht"),
    LOCK_SCREEN("Bildschirm sperren"),
    SETTINGS("Launcher-Einstellungen"),
}

@Serializable
data class GestureSettings(
    val swipeUp: GestureAction = GestureAction.APP_LIBRARY,
    val swipeDown: GestureAction = GestureAction.SEARCH,
    val swipeUpHold: GestureAction = GestureAction.OVERVIEW,
    val doubleTap: GestureAction = GestureAction.LOCK_SCREEN,
    /** Swipe down starting in the upper right area opens the Control Center. */
    val topRightControlCenter: Boolean = true,
    /** Swipe down starting in the upper left area opens the notifications. */
    val topLeftNotifications: Boolean = true,
    val haptics: Boolean = true,
)

@Serializable
data class ClockSettings(
    val style: ClockStyle = ClockStyle.DIGITAL,
    /** null = follow system setting. */
    val use24h: Boolean? = null,
    val showSeconds: Boolean = false,
    val showDate: Boolean = true,
    /** 0 = thin … 3 = bold */
    val weight: Int = 1,
)

@Serializable
data class LauncherSettings(
    // Wallpaper
    val wallpaperMode: WallpaperMode = WallpaperMode.PRESET,
    val wallpaperPreset: Int = 0,
    val customWallpaperFile: String? = null,
    val wallpaperDim: Float = 0f,
    // Icons & grid
    val iconSizeDp: Float = 60f,
    val iconSpacing: Float = 1f,
    val labelScale: Float = 1f,
    val showLabels: Boolean = true,
    val gridColumns: Int = 4,
    val gridRows: Int = 6,
    val iconStyle: IconStyle = IconStyle.DEFAULT,
    val iconPackPackage: String? = null,
    val maskIconPackIcons: Boolean = false,
    // Dock
    val dockScale: Float = 1f,
    val dockOpacity: Float = 0.5f,
    val dockMaxApps: Int = 4,
    // Glass & motion
    val blurStrength: Float = 0.7f,
    val animationsEnabled: Boolean = true,
    val animationSpeed: Float = 1f,
    val reduceTransparency: Boolean = false,
    val liquidRefraction: Boolean = true,
    // Look
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentIndex: Int = 0,
    val clock: ClockSettings = ClockSettings(),
    // Behaviour
    val gestures: GestureSettings = GestureSettings(),
    val allowRotation: Boolean = true,
    val addNewAppsToHome: Boolean = true,
    val searchShowSuggestions: Boolean = true,
    val onboardingDone: Boolean = false,
)
