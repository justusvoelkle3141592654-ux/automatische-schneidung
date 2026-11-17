package de.lumen.launcher.system

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class NotificationEntry(
    val key: String,
    val packageName: String,
    val user: UserHandle,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val smallIcon: ImageBitmap?,
    val largeIcon: ImageBitmap?,
    val clearable: Boolean,
    val contentIntent: PendingIntent?,
    val autoCancel: Boolean,
    val color: Int,
)

/**
 * Bridge between [LumenNotificationService] (bound by the system once the user grants access) and
 * the UI. Nothing is stored or transmitted; the list only lives in memory.
 */
object NotificationHub {
    private val _items = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val items: StateFlow<List<NotificationEntry>> = _items.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    internal var service: LumenNotificationService? = null

    internal fun publish(list: List<NotificationEntry>) { _items.value = list }
    internal fun setConnected(c: Boolean) { _connected.value = c }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, LumenNotificationService::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun dismiss(key: String) {
        service?.cancelNotification(key)
    }

    fun dismissAll() {
        service?.cancelAllNotifications()
    }

    /** Opens a notification's content intent with the launcher's foreground privileges. */
    @SuppressLint("NewApi", "WrongConstant")
    fun open(context: Context, entry: NotificationEntry): Boolean {
        val pi = entry.contentIntent ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            if (entry.autoCancel) dismiss(entry.key)
            true
        } catch (t: Throwable) {
            Log.w("NotificationHub", "open failed", t)
            false
        }
    }
}

class LumenNotificationService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { publishAll() }

    override fun onListenerConnected() {
        NotificationHub.service = this
        NotificationHub.setConnected(true)
        publishAll()
        MediaHub.onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        NotificationHub.service = null
        NotificationHub.setConnected(false)
        NotificationHub.publish(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = schedule()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = schedule()

    private fun schedule() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 120)
    }

    private fun publishAll() {
        val list = try { activeNotifications?.toList().orEmpty() } catch (t: Throwable) { emptyList() }
        NotificationHub.publish(list.mapNotNull { toEntry(it) }.sortedByDescending { it.postTime })
    }

    private fun toEntry(sbn: StatusBarNotification): NotificationEntry? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Media playback is shown in the media controls, not as a card.
        if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (t: Throwable) { sbn.packageName }
        return NotificationEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            user = sbn.user,
            appLabel = label,
            title = title,
            text = text,
            postTime = sbn.postTime,
            smallIcon = iconBitmap(n.smallIcon, sbn.packageName, 64),
            largeIcon = iconBitmap(n.getLargeIcon(), sbn.packageName, 128),
            clearable = sbn.isClearable,
            contentIntent = n.contentIntent,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            color = n.color,
        )
    }

    private fun iconBitmap(icon: Icon?, pkg: String, size: Int): ImageBitmap? = try {
        icon?.loadDrawable(createPackageContext(pkg, 0))?.toBitmap(size, size, Bitmap.Config.ARGB_8888)?.asImageBitmap()
    } catch (t: Throwable) {
        try { icon?.loadDrawable(this)?.toBitmap(size, size)?.asImageBitmap() } catch (_: Throwable) { null }
    }
}

// ============================================================================ media

@Immutable
data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val duration: Long,
    val position: Long,
)

/**
 * Active media session (music widget and Control Center). Uses the official
 * MediaSessionManager API, which is only available to an enabled notification listener.
 */
object MediaHub {
    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() { attach(null) }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    internal fun onListenerConnected(context: Context) {
        try {
            val m = context.getSystemService(MediaSessionManager::class.java)
            manager = m
            val cn = ComponentName(context, LumenNotificationService::class.java)
            m.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            pick(m.getActiveSessions(cn))
        } catch (t: Throwable) {
            Log.w("MediaHub", "sessions unavailable", t)
        }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        attach(best)
    }

    private fun attach(c: MediaController?) {
        if (controller?.sessionToken == c?.sessionToken && c != null) { update(); return }
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        update()
    }

    private fun update() {
        val c = controller
        if (c == null) { _media.value = null; return }
        val md = c.metadata
        val st = c.playbackState
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        _media.value = MediaInfo(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = art?.let { runCatching { Bitmap.createScaledBitmap(it, 256, 256, true).asImageBitmap() }.getOrNull() },
            playing = st?.state == PlaybackState.STATE_PLAYING,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = st?.position ?: 0L,
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun openApp(context: Context) {
        val pi = controller?.sessionActivity
        try {
            if (pi != null) pi.send() else controller?.packageName?.let { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } catch (t: Throwable) { Log.w("MediaHub", "open", t) }
    }
}

/** "jetzt", "vor 5 Min.", "14:32", "gestern", "Mo." … */
object RelativeTime {
    fun format(time: Long, now: Long, locale: java.util.Locale = java.util.Locale.GERMANY): String {
        val diff = (now - time).coerceAtLeast(0)
        val min = diff / 60_000
        if (min < 1) return "jetzt"
        if (min < 60) return "vor $min Min."
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
        val sameDay = cal.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && cal.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return java.text.SimpleDateFormat("HH:mm", locale).format(time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && cal.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        if (yesterday) return "gestern"
        if (diff < 6L * 24 * 3600 * 1000) return java.text.SimpleDateFormat("EEE", locale).format(time)
        return java.text.SimpleDateFormat("d. MMM", locale).format(time)
    }
}
