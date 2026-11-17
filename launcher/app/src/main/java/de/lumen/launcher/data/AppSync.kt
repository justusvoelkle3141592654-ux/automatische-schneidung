package de.lumen.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import de.lumen.launcher.core.JsonStore
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.AppRepository
import de.lumen.launcher.data.apps.LibraryState
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps the home layout in sync with installed apps:
 * - first start: builds a pleasant default layout,
 * - new installs: optionally added to the home screen (setting),
 * - uninstalls: removed from pages, folders and dock.
 */
class AppSync(
    private val context: Context,
    private val repo: AppRepository,
    private val layout: JsonStore<HomeLayout>,
    private val library: JsonStore<LibraryState>,
    private val settings: JsonStore<LauncherSettings>,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            combine(repo.loaded, repo.apps) { loaded, apps -> loaded to apps }
                .filter { it.first }
                .collect { (_, apps) -> sync(apps) }
        }
    }

    private fun sync(apps: List<AppInfo>) {
        if (apps.isEmpty()) return
        val installed = apps.map { it.key }.toSet()
        val s = settings.value
        if (!layout.value.initialized) {
            val (dock, fav) = resolveDefaults(apps)
            val others = apps.filter { !it.isWorkProfile }.map { it.key }
            layout.set(LayoutOps.buildDefault(dock, fav, others, s.gridColumns, s.gridRows))
            library.update { it.copy(knownApps = installed.map { k -> k.value }.toSet()) }
            return
        }
        // Remove uninstalled apps, but only for profiles that are currently available
        // (a paused work profile must not wipe its apps from the layout).
        val activeSerials = apps.map { it.userSerial }.toSet()
        val gone = layout.value.placedApps().filter { it !in installed && it.userSerial in activeSerials }.toSet()
        if (gone.isNotEmpty()) layout.update { LayoutOps.removeApps(it, gone) }

        val known = library.value.knownApps
        val fresh = apps.filter { it.key.value !in known }.map { it.key }
        if (fresh.isNotEmpty()) {
            if (s.addNewAppsToHome) layout.update { LayoutOps.addApps(it, fresh, s.gridColumns, s.gridRows) }
            library.update { it.copy(knownApps = it.knownApps + fresh.map { k -> k.value }) }
        }
    }

    /** Resolves the user's default apps through standard intents. */
    private fun resolveDefaults(apps: List<AppInfo>): Pair<List<AppKey>, List<AppKey>> {
        val pm = context.packageManager
        val main = apps.filter { !it.isWorkProfile }
        fun pkgFor(intent: Intent): AppKey? {
            val ri = try { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (t: Throwable) { null }
            var pkg = ri?.activityInfo?.packageName
            if (pkg == null || pkg == "android" || main.none { it.packageName == pkg }) {
                pkg = try {
                    pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.firstOrNull { p -> main.any { it.packageName == p } }
                } catch (t: Throwable) { null }
            }
            return main.firstOrNull { it.packageName == pkg }?.key
        }
        fun cat(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

        val dock = listOfNotNull(
            pkgFor(Intent(Intent.ACTION_DIAL)),
            pkgFor(cat(Intent.CATEGORY_APP_MESSAGING)),
            pkgFor(cat(Intent.CATEGORY_APP_BROWSER)),
            pkgFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        ).distinct()

        val favIntents = buildList {
            add(cat(Intent.CATEGORY_APP_GALLERY))
            add(cat(Intent.CATEGORY_APP_CALENDAR))
            add(cat(Intent.CATEGORY_APP_EMAIL))
            add(cat(Intent.CATEGORY_APP_MAPS))
            add(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            add(cat(Intent.CATEGORY_APP_MUSIC))
            add(cat(Intent.CATEGORY_APP_CONTACTS))
            add(cat(Intent.CATEGORY_APP_CALCULATOR))
            if (Build.VERSION.SDK_INT >= 29) add(cat(Intent.CATEGORY_APP_FILES))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        val fav = favIntents.mapNotNull(::pkgFor).toMutableList()
        main.firstOrNull { it.packageName == "com.android.vending" }?.let { fav += it.key }
        return dock to fav.distinct().filterNot { it in dock }
    }
}
