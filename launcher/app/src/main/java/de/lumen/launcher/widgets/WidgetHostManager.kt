package de.lumen.launcher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Hosts native Android app widgets (AppWidgetHost). Views are cached per id so moving a widget
 * between pages does not recreate it.
 */
class WidgetHostManager(private val context: Context) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host = LumenAppWidgetHost(context, HOST_ID)
    private val views = HashMap<Int, LumenWidgetHostView>()

    fun startListening() = try { host.startListening() } catch (t: Throwable) { Log.w(TAG, "startListening", t) }
    fun stopListening() = try { host.stopListening() } catch (t: Throwable) { Log.w(TAG, "stopListening", t) }

    fun providers(): List<AppWidgetProviderInfo> {
        val um = context.getSystemService(UserManager::class.java)
        return um.userProfiles.flatMap { u ->
            try { manager.getInstalledProvidersForProfile(u) } catch (t: Throwable) { emptyList() }
        }
    }

    fun info(id: Int): AppWidgetProviderInfo? = try { manager.getAppWidgetInfo(id) } catch (t: Throwable) { null }

    fun allocate(): Int = host.allocateAppWidgetId()

    fun delete(id: Int) {
        views.remove(id)
        try { host.deleteAppWidgetId(id) } catch (t: Throwable) { Log.w(TAG, "delete", t) }
    }

    /** Binds without UI if Android allows it (e.g. permission already granted). */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean = try {
        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
    } catch (t: Throwable) { false }

    fun view(id: Int, info: AppWidgetProviderInfo): LumenWidgetHostView =
        views.getOrPut(id) { host.createView(context.applicationContext, id, info) as LumenWidgetHostView }

    /** Grid span for a provider, based on its declared size and the current cell size (dp). */
    fun span(info: AppWidgetProviderInfo, cellWdp: Float, cellHdp: Float, cols: Int, rows: Int): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, cols) to info.targetCellHeight.coerceIn(1, rows)
        }
        val d = context.resources.displayMetrics.density
        val w = info.minWidth / d
        val h = info.minHeight / d
        return ceil(w / cellWdp).toInt().coerceIn(1, cols) to ceil(h / cellHdp).toInt().coerceIn(1, rows)
    }

    fun updateSize(id: Int, wDp: Int, hDp: Int) {
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        try { manager.updateAppWidgetOptions(id, opts) } catch (_: Throwable) {}
    }

    fun providerKey(info: AppWidgetProviderInfo): String = info.provider.flattenToString()

    fun findProvider(key: String): AppWidgetProviderInfo? =
        providers().firstOrNull { it.provider == ComponentName.unflattenFromString(key) }

    companion object {
        const val HOST_ID = 0x4C55 // "LU"
        private const val TAG = "WidgetHost"
    }
}

class LumenAppWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LumenWidgetHostView(context)
}

/**
 * Host view that reports long presses (for the launcher's context menu and drag & drop) and
 * blocks touches into the widget while the home screen is in edit mode.
 */
class LumenWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    var onEditPress: ((x: Float, y: Float) -> Unit)? = null
    var editMode: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        // From the next event on we intercept; Android then sends ACTION_CANCEL to the widget.
        onLongPress?.invoke(downX, downY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onEditPress?.invoke(ev.x, ev.y)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; longPressed = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return longPressed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode || longPressed) return true
        return super.onTouchEvent(event)
    }
}
