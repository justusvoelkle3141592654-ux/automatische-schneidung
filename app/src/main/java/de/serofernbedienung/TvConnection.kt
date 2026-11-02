package de.serofernbedienung

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * WebSocket-Verbindung zum Samsung-Tizen-Fernseher (Kanal samsung.remote.control, Port 8002).
 * Nachrichtenformat nach der Open-Source-Bibliothek samsungtvws (xchwarze/samsung-tv-ws-api).
 */
class TvConnection(private val prefs: Prefs, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String, connected: Boolean)
        fun onApps(apps: List<TvApp>)
        fun onImeActive(active: Boolean)
        fun onInfo(text: String)
    }

    data class TvApp(val name: String, val appId: String, val appType: Int)

    private val main = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    var connected = false
        private set

    private fun http(): OkHttpClient = client ?: TvHttp.client(prefs).also { client = it }

    private fun baseUrl(ip: String): HttpUrl.Builder =
        HttpUrl.Builder().scheme("https").host(ip).port(PORT)

    fun connect() {
        val ip = prefs.ip ?: run { post("Keine IP gespeichert", false); return }
        disconnect()
        val url = baseUrl(ip)
            .addPathSegments("api/v2/channels/samsung.remote.control")
            .addQueryParameter("name", Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP))
            .apply { prefs.token?.let { addQueryParameter("token", it) } }
            .build()
        post(
            if (prefs.token == null) "Verbinde … bitte am Fernseher bestätigen" else "Verbinde …",
            false
        )
        socket = http().newWebSocket(Request.Builder().url(url).build(), wsListener)
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        connected = false
    }

    /** Nach Änderung der IP oder Zurücksetzen der Kopplung neuen Client bauen. */
    fun resetClient() {
        disconnect()
        client = null
    }

    private val wsListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val msg = try { JSONObject(text) } catch (e: Exception) { return }
            val data = msg.optJSONObject("data")
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    data?.optString("token")?.takeIf { it.isNotEmpty() }?.let { prefs.token = it }
                    connected = true
                    post("Verbunden mit ${prefs.ip}", true)
                }
                "ms.channel.unauthorized" -> {
                    connected = false
                    post("Nicht autorisiert – Verbindung am Fernseher abgelehnt?", false)
                }
                "ms.error" -> main.post {
                    listener.onInfo("Fehler vom Fernseher: " + (data?.optString("message") ?: text))
                }
                "ed.installedApp.get" -> {
                    val list = parseApps(data?.optJSONArray("data"))
                    main.post { listener.onApps(list) }
                }
                "ms.remote.imeStart" -> main.post { listener.onImeActive(true) }
                "ms.remote.imeEnd" -> main.post { listener.onImeActive(false) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            connected = false
            post("Getrennt", false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            connected = false
            post("Keine Verbindung: ${t.message ?: t.javaClass.simpleName}", false)
        }
    }

    private fun parseApps(arr: JSONArray?): List<TvApp> {
        if (arr == null) return emptyList()
        val out = ArrayList<TvApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("appId")
            if (id.isEmpty()) continue
            out += TvApp(o.optString("name", id), id, o.optInt("app_type", 0))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun post(text: String, ok: Boolean) = main.post { listener.onStatus(text, ok) }

    private fun send(method: String, params: JSONObject): Boolean {
        val s = socket
        if (s == null || !connected) {
            main.post { listener.onInfo("Nicht verbunden") }
            return false
        }
        return s.send(JSONObject().put("method", method).put("params", params).toString())
    }

    private fun remoteKey(cmd: String, key: String) = send(
        "ms.remote.control",
        JSONObject()
            .put("Cmd", cmd)
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
    )

    fun sendKey(key: String) = remoteKey("Click", key)

    /** Taste gedrückt halten: Press, warten, Release (wie samsungtvws hold). */
    fun holdKey(key: String, seconds: Double) {
        if (!remoteKey("Press", key)) return
        main.postDelayed({ remoteKey("Release", key) }, (seconds * 1000).toLong())
    }

    fun requestApps() = send(
        "ms.channel.emit",
        JSONObject().put("event", "ed.installedApp.get").put("to", "host")
    )

    /** app_type 2 → DEEP_LINK, sonst NATIVE_LAUNCH (Hinweis aus samsungtvws). */
    fun launchApp(appId: String, appType: Int) = send(
        "ms.channel.emit",
        JSONObject()
            .put("event", "ed.apps.launch")
            .put("to", "host")
            .put(
                "data", JSONObject()
                    .put("action_type", if (appType == 2) "DEEP_LINK" else "NATIVE_LAUNCH")
                    .put("appId", appId)
                    .put("metaTag", "")
            )
    )

    private var textBroadcastSent = false

    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (!textBroadcastSent) {
            // Manche Fernseher erwarten diese Nachricht vor der ersten Texteingabe (samsungtvws).
            send(
                "ms.channel.emit",
                JSONObject().put("event", "custom.remote.textReceived").put("to", "broadcast")
            )
            textBroadcastSent = true
        }
        send(
            "ms.remote.control",
            JSONObject()
                .put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
                .put("DataOfCmd", "base64")
                .put("TypeOfRemote", "SendInputString")
        )
    }

    fun endText() {
        send("ms.remote.control", JSONObject().put("TypeOfRemote", "SendInputEnd"))
        textBroadcastSent = false
    }

    /** Geräteinfo über REST (GET https://IP:8002/api/v2/). Liefert u. a. wifiMac. */
    fun fetchDeviceInfo(onResult: (JSONObject?, String?) -> Unit) {
        val ip = prefs.ip ?: return onResult(null, "Keine IP gespeichert")
        val url = baseUrl(ip).addPathSegments("api/v2/").build()
        http().newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onResult(null, e.message ?: e.javaClass.simpleName) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() }
                val json = try { body?.let { JSONObject(it) } } catch (e: Exception) { null }
                main.post { onResult(json, if (json == null) "Ungültige Antwort (HTTP ${response.code})" else null) }
            }
        })
    }

    /** Alternativer App-Start über REST (POST https://IP:8002/api/v2/applications/<id>). */
    fun launchAppRest(appId: String, onResult: (String) -> Unit) {
        val ip = prefs.ip ?: return onResult("Keine IP gespeichert")
        val url = baseUrl(ip).addPathSegments("api/v2/applications").addPathSegment(appId).build()
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        http().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onResult("REST-Fehler: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string().orEmpty() }
                main.post { onResult("REST HTTP ${response.code}: ${body.take(200)}") }
            }
        })
    }

    companion object {
        const val PORT = 8002
        const val APP_NAME = "SeroFernbedienung"
    }
}
