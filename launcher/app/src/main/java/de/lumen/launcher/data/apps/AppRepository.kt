package de.lumen.launcher.data.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * Source of truth for installed, launchable apps (all user profiles, incl. work profile).
 * Uses the official [LauncherApps] API and reacts to installs/updates/removals.
 */
class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    @Volatile
    private var activityInfos: Map<AppKey, LauncherActivityInfo> = emptyMap()

    private var reloadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = Unit
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        scheduleReload(0)
    }

    fun scheduleReload(delayMs: Long = 300) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(delayMs)
            reload()
        }
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        val infos = HashMap<AppKey, LauncherActivityInfo>()
        val result = ArrayList<AppInfo>()
        val me = Process.myUserHandle()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val list = try {
                launcherApps.getActivityList(null, user)
            } catch (t: Throwable) {
                Log.w(TAG, "getActivityList failed for $user", t); emptyList()
            }
            for (info in list) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val key = AppKey.of(cn.packageName, cn.className, serial)
                val ai = info.applicationInfo
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                infos[key] = info
                result += AppInfo(
                    key = key,
                    label = label,
                    packageName = cn.packageName,
                    className = cn.className,
                    user = user,
                    userSerial = serial,
                    isWorkProfile = user != me,
                    systemCategory = AppCategorizer.categorize(cn.packageName, label, ai.category),
                    firstInstallTime = info.firstInstallTime,
                    isSystemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        result.sortWith { a, b -> collator.compare(a.label, b.label) }
        activityInfos = infos
        _apps.value = result
        _loaded.value = true
    }

    /** Test hook: injects a fixed app list (used by JVM screenshot tests). */
    @androidx.annotation.VisibleForTesting
    fun setAppsForTest(list: List<AppInfo>) {
        reloadJob?.cancel()
        _apps.value = list
        _loaded.value = true
    }

    fun activityInfo(key: AppKey): LauncherActivityInfo? = activityInfos[key]

    fun find(key: AppKey): AppInfo? = _apps.value.firstOrNull { it.key == key }

    /**
     * Starts an app. [sourceBounds] are the on-screen icon bounds so the system can animate
     * the window growing out of the icon (official ActivityOptions scale-up animation).
     */
    fun launch(app: AppInfo, view: View?, sourceBounds: Rect?): Boolean {
        val opts: Bundle? = if (view != null && sourceBounds != null) {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            ActivityOptions.makeScaleUpAnimation(
                view,
                sourceBounds.left - loc[0], sourceBounds.top - loc[1],
                sourceBounds.width(), sourceBounds.height(),
            ).toBundle()
        } else null
        return try {
            launcherApps.startMainActivity(ComponentName(app.packageName, app.className), app.user, sourceBounds, opts)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Launch failed for ${app.key}", t)
            false
        }
    }

    fun openAppInfo(app: AppInfo) {
        try {
            launcherApps.startAppDetailsActivity(ComponentName(app.packageName, app.className), app.user, null, null)
        } catch (t: Throwable) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun uninstall(app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Uninstall failed", t)
        }
    }

    /** App shortcuts (long-press actions); only available while Lumen is the default home app. */
    fun shortcuts(app: AppInfo): List<ShortcutInfo> {
        if (!hasShortcutPermission()) return emptyList()
        return try {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(ComponentName(app.packageName, app.className))
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            (launcherApps.getShortcuts(q, app.user) ?: emptyList())
                .filter { it.isEnabled }
                .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
                .take(4)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun shortcutIcon(shortcut: ShortcutInfo, density: Int) = try {
        launcherApps.getShortcutIconDrawable(shortcut, density)
    } catch (t: Throwable) {
        null
    }

    fun startShortcut(shortcut: ShortcutInfo, bounds: Rect?) {
        try {
            launcherApps.startShortcut(shortcut, bounds, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Shortcut start failed", t)
        }
    }

    fun hasShortcutPermission(): Boolean = try {
        launcherApps.hasShortcutHostPermission()
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "AppRepository"
    }
}
