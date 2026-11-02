package de.serofernbedienung

import android.content.Context

/** Gespeicherte Einstellungen: IP, Token, MAC und Zertifikats-Fingerabdruck des Fernsehers. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sero", Context.MODE_PRIVATE)

    var ip: String?
        get() = sp.getString("ip", null)
        set(v) = sp.edit().putString("ip", v).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var mac: String?
        get() = sp.getString("mac", null)
        set(v) = sp.edit().putString("mac", v).apply()

    /** SHA-256 des TV-Zertifikats, beim ersten Verbinden gemerkt (Trust on first use). */
    var certPin: String?
        get() = sp.getString("certPin", null)
        set(v) = sp.edit().putString("certPin", v).apply()

    fun resetPairing() {
        sp.edit().remove("token").remove("certPin").apply()
    }
}
