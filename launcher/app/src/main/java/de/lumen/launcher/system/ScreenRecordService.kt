package de.lumen.launcher.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.lumen.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recording through the official MediaProjection API.
 * The user confirms every recording in the system dialog; the video (without audio) is saved to
 * Movies/Lumen. Runs as a foreground service of type "mediaProjection" as Android requires.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); stopSelf() }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                startForegroundCompat()
                if (!startRecording(code, data)) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bildschirmaufnahme", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Bildschirm wird aufgenommen")
            .setContentText("Zum Beenden tippen")
            .setContentIntent(stop)
            .addAction(0, "Beenden", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun startRecording(code: Int, data: Intent): Boolean {
        return try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data) ?: return false
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording(); stopSelf() }
            }, null)

            val metrics = resources.displayMetrics
            // Encode at most 1080 px on the short edge, even dimensions.
            val scale = (1080f / minOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
            val w = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val h = ((metrics.heightPixels * scale).toInt() / 2) * 2

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(8_000_000)
            openOutput(r)
            r.prepare()
            recorder = r
            display = mp.createVirtualDisplay(
                "LumenRecord", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
            )
            r.start()
            _recording.value = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            stopRecording()
            false
        }
    }

    private fun openOutput(r: MediaRecorder) {
        val name = "Lumen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Lumen")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
            pendingUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "w") ?: error("No file descriptor")
            pfd = fd
            r.setOutputFile(fd.fileDescriptor)
        } else {
            // Android 8/9: app-specific movies folder (no storage permission needed).
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Lumen").apply { mkdirs() }
            r.setOutputFile(File(dir, name).absolutePath)
        }
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (t: Throwable) { Log.w(TAG, "stop", t) }
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        display?.release(); display = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        pendingUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } catch (t: Throwable) { Log.w(TAG, "publish", t) }
            }
        }
        pendingUri = null
        _recording.value = false
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenRecord"
        private const val CHANNEL = "screen_record"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "de.lumen.launcher.record.START"
        const val ACTION_STOP = "de.lumen.launcher.record.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private val _recording = MutableStateFlow(false)
        val recording: StateFlow<Boolean> = _recording.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
