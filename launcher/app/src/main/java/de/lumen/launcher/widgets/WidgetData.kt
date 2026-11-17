package de.lumen.launcher.widgets

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.compose.runtime.Immutable
import de.lumen.launcher.core.JsonStore
import de.lumen.launcher.core.LumenJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ============================================================================ weather

@Serializable
data class DayForecast(val date: String, val code: Int, val max: Double, val min: Double)

@Serializable
data class HourForecast(val time: String, val code: Int, val temp: Double)

@Serializable
data class WeatherData(
    val fetchedAt: Long,
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val daily: List<DayForecast>,
    val hourly: List<HourForecast>,
)

@Serializable
data class Place(val name: String, val region: String, val lat: Double, val lon: Double)

/** WMO weather interpretation codes (as used by Open-Meteo) → German description. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teilweise bewölkt"
        3 -> "Bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"
        56, 57 -> "Gefrierender Niesel"
        61, 63, 65 -> "Regen"
        66, 67 -> "Gefrierender Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Regenschauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "Unbekannt"
    }

    enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    fun kind(code: Int): Kind = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUDY
    }
}

@Serializable
private data class WeatherCache(val entries: Map<String, WeatherData> = emptyMap())

/**
 * Weather from Open-Meteo (free, no API key, no account). Network is used only while a weather
 * widget with a configured place exists; results are cached for 30 minutes.
 */
class WeatherRepository(context: Context, private val scope: CoroutineScope) {
    private val cache = JsonStore(File(File(context.filesDir, "state"), "weather.json"), WeatherCache.serializer(), { WeatherCache() }, scope)
    private val _data = MutableStateFlow(cache.value.entries)
    val data: StateFlow<Map<String, WeatherData>> = _data.asStateFlow()

    @androidx.annotation.VisibleForTesting
    fun putForTest(lat: Double, lon: Double, d: WeatherData) { _data.value = _data.value + (key(lat, lon) to d) }

    fun key(lat: Double, lon: Double) = "%.3f,%.3f".format(java.util.Locale.US, lat, lon)

    fun refresh(lat: Double, lon: Double, force: Boolean = false) {
        val k = key(lat, lon)
        val cached = _data.value[k]
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 30 * 60_000) return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { fetch(lat, lon) } ?: return@launch
            _data.value = _data.value + (k to fresh)
            cache.update { it.copy(entries = it.entries + (k to fresh)) }
        }
    }

    private fun fetch(lat: Double, lon: Double): WeatherData? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&hourly=temperature_2m,weather_code&forecast_days=6&forecast_hours=6&timezone=auto"
        parseForecast(httpGet(url), System.currentTimeMillis())
    } catch (t: Throwable) {
        Log.w("Weather", "fetch failed", t); null
    }

    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parsePlaces(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=de&format=json"))
        } catch (t: Throwable) {
            Log.w("Weather", "geocoding failed", t); emptyList()
        }
    }

    private fun httpGet(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        con.setRequestProperty("User-Agent", "LumenLauncher")
        try {
            if (con.responseCode !in 200..299) error("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    companion object {
        fun parseForecast(json: String, now: Long): WeatherData {
            val root = LumenJson.parseToJsonElement(json).jsonObject
            val cur = root.getValue("current").jsonObject
            val daily = root.getValue("daily").jsonObject
            val hourly = root["hourly"]?.jsonObject
            fun arr(o: JsonObject, k: String) = o.getValue(k).jsonArray
            val days = arr(daily, "time").indices.map { i ->
                DayForecast(
                    arr(daily, "time")[i].jsonPrimitive.content,
                    arr(daily, "weather_code")[i].jsonPrimitive.int,
                    arr(daily, "temperature_2m_max")[i].jsonPrimitive.double,
                    arr(daily, "temperature_2m_min")[i].jsonPrimitive.double,
                )
            }
            val hours = hourly?.let { h ->
                arr(h, "time").indices.map { i ->
                    HourForecast(arr(h, "time")[i].jsonPrimitive.content, arr(h, "weather_code")[i].jsonPrimitive.int, arr(h, "temperature_2m")[i].jsonPrimitive.double)
                }
            }.orEmpty()
            return WeatherData(
                fetchedAt = now,
                temp = cur.getValue("temperature_2m").jsonPrimitive.double,
                code = cur.getValue("weather_code").jsonPrimitive.int,
                isDay = cur["is_day"]?.jsonPrimitive?.int != 0,
                daily = days,
                hourly = hours,
            )
        }

        fun parsePlaces(json: String): List<Place> {
            val root = LumenJson.parseToJsonElement(json).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()
            return results.map { r ->
                val o = r.jsonObject
                Place(
                    name = o.getValue("name").jsonPrimitive.content,
                    region = listOfNotNull(o["admin1"]?.jsonPrimitive?.content, o["country"]?.jsonPrimitive?.content).joinToString(", "),
                    lat = o.getValue("latitude").jsonPrimitive.double,
                    lon = o.getValue("longitude").jsonPrimitive.double,
                )
            }
        }
    }
}

// ============================================================================ calendar

@Immutable
data class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val eventId: Long)

/** Upcoming events from the system calendar provider (READ_CALENDAR, requested on demand). */
class CalendarRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    private var observing = false

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasPermission()) { _events.value = emptyList(); return }
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            })
        }
        scope.launch {
            _events.value = withContext(Dispatchers.IO) { query() }
        }
    }

    private fun query(): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfDay)
            ContentUris.appendId(it, now + 7L * 24 * 3600 * 1000)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances._ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_ID,
        )
        val out = ArrayList<CalendarEvent>()
        return try {
            context.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    val end = c.getLong(3)
                    val allDay = c.getInt(4) == 1
                    if (!allDay && end < now) continue
                    out += CalendarEvent(c.getLong(0), c.getString(1) ?: "(Ohne Titel)", c.getLong(2), end, allDay, c.getInt(5), c.getLong(6))
                }
            }
            out
        } catch (t: Throwable) {
            Log.w("Calendar", "query failed", t); emptyList()
        }
    }

    fun openEvent(e: CalendarEvent) {
        val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) {}
    }
}

// ============================================================================ battery

@Immutable
data class BatteryInfo(val level: Int, val charging: Boolean, val temperatureC: Float, val plugged: Boolean)

class BatteryMonitor(context: Context) {
    private val _info = MutableStateFlow(BatteryInfo(-1, false, 0f, false))
    val info: StateFlow<BatteryInfo> = _info.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = update(i)
        }
        // ACTION_BATTERY_CHANGED is a sticky system broadcast; registering returns the last value.
        val sticky = androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::update)
    }

    private fun update(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        _info.value = BatteryInfo(
            level = if (level < 0) -1 else level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }
}

// ============================================================================ reminders

@Serializable
data class Reminder(val id: Long, val text: String, val done: Boolean = false, val created: Long = 0)

@Serializable
data class ReminderList(val items: List<Reminder> = emptyList(), val nextId: Long = 1)
