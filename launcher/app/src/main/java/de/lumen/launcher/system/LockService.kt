package de.lumen.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service whose only purpose is locking the screen on double tap
 * (GLOBAL_ACTION_LOCK_SCREEN, Android 9+) and opening the system notification shade.
 * It does not read window content (canRetrieveWindowContent = false).
 */
class LumenLockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: LumenLockService? = null

        val isRunning: Boolean get() = instance != null

        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        fun openSystemNotifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, LumenLockService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
