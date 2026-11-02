package de.serofernbedienung

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity(), TvConnection.Listener {

    private lateinit var prefs: Prefs
    private lateinit var tv: TvConnection
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        tv = TvConnection(prefs, this)
        txtStatus = findViewById(R.id.txtStatus)

        setupTabs()
        bindKeyButtons(findViewById(android.R.id.content))
        setupSetupPage()
        setupAppsPage()
        findViewById<Button>(R.id.btnWol).setOnClickListener { wakeOnLan() }

        if (prefs.ip == null) {
            findViewById<TabLayout>(R.id.tabs).getTabAt(2)?.select()
        }
    }

    override fun onStart() {
        super.onStart()
        if (prefs.ip != null && !tv.connected) tv.connect()
    }

    override fun onStop() {
        super.onStop()
        tv.disconnect()
    }

    // ---------- Oberfläche ----------

    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.tabs)
        val pages = listOf(R.id.pageRemote, R.id.pageApps, R.id.pageSetup).map { findViewById<View>(it) }
        listOf("Fernbedienung", "Apps & Text", "Einstellungen").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pages.forEachIndexed { i, v -> v.visibility = if (i == tab.position) View.VISIBLE else View.GONE }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /** Alle Buttons mit einem Tag "KEY_..." senden diesen Key-Code. */
    private fun bindKeyButtons(v: View) {
        if (v is Button) {
            val tag = v.tag as? String
            if (tag != null && tag.startsWith("KEY_")) v.setOnClickListener { tv.sendKey(tag) }
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) bindKeyButtons(v.getChildAt(i))
        }
    }

    private fun text(id: Int): String = findViewById<EditText>(id).text?.toString()?.trim().orEmpty()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Einstellungen ----------

    private fun setupSetupPage() {
        findViewById<EditText>(R.id.editIp).setText(prefs.ip.orEmpty())
        findViewById<EditText>(R.id.editMac).setText(prefs.mac.orEmpty())

        findViewById<Button>(R.id.btnSaveIp).setOnClickListener { saveIp(text(R.id.editIp)) }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { tv.connect() }
        findViewById<Button>(R.id.btnResetPair).setOnClickListener {
            prefs.resetPairing()
            tv.resetClient()
            onStatus("Kopplung zurückgesetzt", false)
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }

        findViewById<Button>(R.id.btnSaveMac).setOnClickListener {
            val mac = text(R.id.editMac)
            if (WakeOnLan.parseMac(mac) == null) toast("Ungültige MAC-Adresse") else {
                prefs.mac = mac.uppercase()
                toast("MAC gespeichert")
            }
        }
        findViewById<Button>(R.id.btnInfo).setOnClickListener {
            val out = findViewById<TextView>(R.id.txtInfo)
            out.text = "Frage Geräteinfo ab …"
            tv.fetchDeviceInfo { json, err ->
                if (json == null) {
                    out.text = "Fehler: $err"
                    return@fetchDeviceInfo
                }
                out.text = json.toString(2)
                val mac = json.optJSONObject("device")?.optString("wifiMac").orEmpty()
                if (WakeOnLan.parseMac(mac) != null) {
                    prefs.mac = mac.uppercase()
                    findViewById<EditText>(R.id.editMac).setText(prefs.mac)
                    toast("WLAN-MAC übernommen")
                }
            }
        }
        findViewById<Button>(R.id.btnSendKey).setOnClickListener {
            val key = text(R.id.editKey).uppercase()
            if (key.isEmpty()) return@setOnClickListener
            val hold = text(R.id.editHold).replace(',', '.').toDoubleOrNull()
            if (hold != null && hold > 0) tv.holdKey(key, hold) else tv.sendKey(key)
        }
    }

    private fun saveIp(ip: String) {
        if (!IPV4.matches(ip) || ip.split('.').any { it.toInt() > 255 }) {
            toast("Ungültige IP-Adresse")
            return
        }
        if (ip != prefs.ip) {
            prefs.ip = ip
            prefs.resetPairing()
        }
        findViewById<EditText>(R.id.editIp).setText(ip)
        tv.resetClient()
        tv.connect()
    }

    private fun scan() {
        val list = findViewById<LinearLayout>(R.id.scanList)
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Suche läuft (bis ca. 30 s) …" })
        Thread {
            val found = try { NetworkScan.scan(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                list.removeAllViews()
                list.addView(TextView(this).apply {
                    text = if (found.isEmpty()) "Nichts gefunden. Bitte IP manuell eingeben (am Fernseher unter Einstellungen > Allgemein > Netzwerk > Netzwerkstatus)."
                    else "Geräte mit offenem Port 8002 (Kandidaten, antippen zum Übernehmen):"
                })
                found.forEach { ip ->
                    list.addView(MaterialButton(this).apply {
                        text = ip
                        setOnClickListener { saveIp(ip); list.removeAllViews() }
                    })
                }
            }
        }.start()
    }

    private fun wakeOnLan() {
        val mac = prefs.mac?.let { WakeOnLan.parseMac(it) }
        if (mac == null) {
            toast("Keine MAC gespeichert (Einstellungen)")
            return
        }
        Thread {
            val msg = try {
                WakeOnLan.send(this, mac)
                "Wake-on-LAN gesendet"
            } catch (e: Exception) {
                "WoL-Fehler: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                // Fernseher braucht nach dem Aufwachen etwas Zeit, dann neu verbinden.
                txtStatus.postDelayed({ if (!tv.connected) tv.connect() }, 8000)
            }
        }.start()
    }

    // ---------- Apps, Text, Spiegelung ----------

    private fun setupAppsPage() {
        findViewById<Button>(R.id.btnLoadApps).setOnClickListener {
            if (tv.requestApps()) findViewById<TextView>(R.id.txtAppsHint).text = "Warte auf Antwort vom Fernseher …"
        }
        findViewById<Button>(R.id.btnAppWs).setOnClickListener {
            val id = text(R.id.editAppId)
            if (id.isNotEmpty()) tv.launchApp(id, 2)
        }
        findViewById<Button>(R.id.btnAppRest).setOnClickListener {
            val id = text(R.id.editAppId)
            if (id.isNotEmpty()) tv.launchAppRest(id) { toast(it) }
        }
        findViewById<Button>(R.id.btnSendText).setOnClickListener {
            val t = findViewById<EditText>(R.id.editText).text?.toString().orEmpty()
            if (t.isNotEmpty()) tv.sendText(t)
        }
        findViewById<Button>(R.id.btnEndText).setOnClickListener { tv.endText() }
        findViewById<Button>(R.id.btnCast).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                toast("Übertragen-Einstellungen auf diesem Handy nicht vorhanden")
            }
        }
    }

    // ---------- TvConnection.Listener ----------

    override fun onStatus(text: String, connected: Boolean) {
        txtStatus.text = text
    }

    override fun onApps(apps: List<TvConnection.TvApp>) {
        val list = findViewById<LinearLayout>(R.id.appList)
        list.removeAllViews()
        findViewById<TextView>(R.id.txtAppsHint).text =
            if (apps.isEmpty()) "Der Fernseher hat keine Apps gemeldet." else "${apps.size} Apps gefunden:"
        apps.forEach { app ->
            list.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = app.name
                setOnClickListener {
                    tv.launchApp(app.appId, app.appType)
                    findViewById<EditText>(R.id.editAppId).setText(app.appId)
                }
            })
        }
    }

    override fun onImeActive(active: Boolean) {
        findViewById<TextView>(R.id.txtIme).text =
            if (active) "Fernseher wartet auf Texteingabe." else "Erst am Fernseher ein Textfeld öffnen (z. B. Suche), dann hier tippen und senden."
    }

    override fun onInfo(text: String) = toast(text)

    companion object {
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
