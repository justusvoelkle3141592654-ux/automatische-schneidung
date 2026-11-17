package de.lumen.launcher.system

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.pow
import kotlin.math.roundToInt

enum class NetworkKind { NONE, WIFI, CELLULAR, OTHER }

data class SystemState(
    val wifiEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val network: NetworkKind = NetworkKind.NONE,
    val airplane: Boolean = false,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val powerSave: Boolean = false,
    val dndOn: Boolean = false,
    val dndAccess: Boolean = false,
    val autoRotate: Boolean = false,
    val canWriteSettings: Boolean = false,
    /** 0..1 perceptual brightness. */
    val brightness: Float = 0.5f,
    val autoBrightness: Boolean = false,
    /** 0..1 media volume. */
    val volume: Float = 0.5f,
)

/**
 * Reads and (where Android allows it) changes system state for the Control Center.
 *
 * Directly switchable with official APIs: flashlight (CameraManager), volume (AudioManager),
 * brightness and auto-rotate (after the user grants "Modify system settings"), Do Not Disturb
 * (after granting notification policy access).
 * Not switchable by third-party apps on current Android versions: Wi-Fi, Bluetooth, battery saver,
 * airplane mode, mobile data. For those the state is shown and a tap opens the official panel.
 */
class SystemControls(private val context: Context) {

    private val _state = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bt: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var torchId: String? = null

    init {
        torchId = try {
            camera.cameraIdList.firstOrNull { id ->
                camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) { null }
        try {
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) _state.update { it.copy(torchOn = enabled) }
                }
            }, main)
        } catch (t: Throwable) { Log.w(TAG, "torch callback", t) }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val kind = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
                        else -> NetworkKind.OTHER
                    }
                    _state.update { it.copy(network = kind) }
                }

                override fun onLost(network: Network) {
                    _state.update { it.copy(network = NetworkKind.NONE) }
                }
            }, main)
        } catch (t: Throwable) { Log.w(TAG, "network callback", t) }

        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val cr = context.contentResolver
        listOf(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
        ).forEach { cr.registerContentObserver(it, false, observer) }
        refresh()
    }

    fun refresh() {
        val cr = context.contentResolver
        val raw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        _state.update {
            it.copy(
                wifiEnabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false),
                bluetoothEnabled = runCatching { bt?.isEnabled == true }.getOrDefault(false),
                airplane = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
                torchAvailable = torchId != null,
                powerSave = power.isPowerSaveMode,
                dndOn = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
                dndAccess = nm.isNotificationPolicyAccessGranted,
                autoRotate = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1,
                canWriteSettings = Settings.System.canWrite(context),
                brightness = (raw.coerceIn(0, 255) / 255f).pow(1f / 2.2f),
                autoBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat(),
            )
        }
    }

    // ------------------------------------------------------------------ direct controls

    fun setTorch(on: Boolean): Boolean {
        val id = torchId ?: return false
        return try { camera.setTorchMode(id, on); true } catch (t: Throwable) { Log.w(TAG, "torch", t); false }
    }

    fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt(), 0)
        } catch (t: SecurityException) { Log.w(TAG, "volume", t) }
        _state.update { it.copy(volume = v) }
    }

    /** Requires WRITE_SETTINGS special access. Returns false when it is missing. */
    fun setBrightness(v: Float): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val raw = (v.coerceIn(0.02f, 1f).pow(2.2f) * 255).roundToInt().coerceIn(1, 255)
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
        _state.update { it.copy(brightness = v, autoBrightness = false) }
        return true
    }

    fun setAutoRotate(on: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        _state.update { it.copy(autoRotate = on) }
        return true
    }

    /** Requires notification policy access. Returns false when it is missing. */
    fun setDnd(on: Boolean): Boolean {
        if (!nm.isNotificationPolicyAccessGranted) return false
        return try {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            _state.update { it.copy(dndOn = on) }
            true
        } catch (t: Throwable) { Log.w(TAG, "dnd", t); false }
    }

    // ------------------------------------------------------------------ official settings screens

    fun intentWifi(): Intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    fun intentInternet(): Intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
    fun intentBluetooth() = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    fun intentAirplane() = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
    fun intentBatterySaver() = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    fun intentDisplay() = Intent(Settings.ACTION_DISPLAY_SETTINGS)
    fun intentSound() = Intent(Settings.ACTION_SOUND_SETTINGS)
    fun intentSettings() = Intent(Settings.ACTION_SETTINGS)
    fun intentWriteSettings() = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun intentDndAccess() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    fun intentVolumePanel(): Intent? = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME) else null

    fun start(intent: Intent?): Boolean {
        intent ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start ${intent.action}", t)
            try {
                context.startActivity(intentSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } catch (_: Throwable) { false }
        }
    }

    companion object { private const val TAG = "SystemControls" }
}
