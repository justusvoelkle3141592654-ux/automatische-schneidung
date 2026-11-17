package de.lumen.launcher.system

import android.Manifest
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/** Default-home status, permission overview and wallpaper file handling. */
object LauncherSystem {

    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val ri = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == context.packageName
    }

    /** The official way to ask for the home role (Android 10+) or open the home app settings. */
    fun defaultHomeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    data class PermissionStatus(
        val notifications: Boolean,
        val writeSettings: Boolean,
        val dnd: Boolean,
        val calendar: Boolean,
        val lockService: Boolean,
        val postNotifications: Boolean,
    )

    fun permissions(context: Context): PermissionStatus = PermissionStatus(
        notifications = NotificationHub.hasAccess(context),
        writeSettings = Settings.System.canWrite(context),
        dnd = context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted,
        calendar = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
        lockService = LumenLockService.isEnabled(context),
        postNotifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    )

    /** Copies a picked image into private storage so it survives the picker's temporary grant. */
    fun importWallpaper(context: Context, uri: Uri): File? = try {
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val out = File(dir, "custom_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        if (out.length() > 0) out else null
    } catch (t: Throwable) {
        Log.w("LauncherSystem", "import wallpaper", t); null
    }

    /** Sets an image as system (home + lock screen) wallpaper via the official WallpaperManager. */
    fun applySystemWallpaper(context: Context, bitmap: android.graphics.Bitmap): Boolean = try {
        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        true
    } catch (t: Throwable) {
        Log.w("LauncherSystem", "set wallpaper", t); false
    }

    fun decode(file: File) = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()

    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) { "?" }
}
