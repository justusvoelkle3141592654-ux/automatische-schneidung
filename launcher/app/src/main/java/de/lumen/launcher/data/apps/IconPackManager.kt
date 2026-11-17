package de.lumen.launcher.data.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class IconPackInfo(val packageName: String, val label: String)

/**
 * Support for third-party icon packs using the widely adopted ADW / Nova "appfilter.xml" format.
 * Icon packs are regular apps; only their public resources are read.
 */
class IconPackManager(private val context: Context) {

    private class LoadedPack(val packageName: String, val res: Resources, val map: Map<String, String>)

    @Volatile
    private var loaded: LoadedPack? = null

    fun installedPacks(): List<IconPackInfo> {
        val pm = context.packageManager
        val actions = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
        return actions.flatMap { action ->
            try {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            } catch (t: Throwable) {
                emptyList()
            }
        }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .map { IconPackInfo(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    @Synchronized
    private fun ensureLoaded(pkg: String): LoadedPack? {
        loaded?.let { if (it.packageName == pkg) return it }
        return try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val map = parseAppFilter(res, pkg)
            LoadedPack(pkg, res, map).also { loaded = it }
        } catch (t: Throwable) {
            Log.w("IconPack", "Could not load icon pack $pkg", t)
            null
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun parseAppFilter(res: Resources, pkg: String): Map<String, String> {
        val map = HashMap<String, String>()
        val id = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (id != 0) {
            res.getXml(id)
        } else {
            val stream = try {
                res.assets.open("appfilter.xml")
            } catch (t: Throwable) {
                return map
            }
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "UTF-8") }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (component != null && drawable != null) {
                    // Format: "ComponentInfo{pkg/cls}"
                    val inner = component.substringAfter('{', "").substringBefore('}', "")
                    if (inner.isNotEmpty()) map[inner] = drawable
                }
            }
            event = parser.next()
        }
        return map
    }

    @SuppressLint("DiscouragedApi")
    fun iconFor(pack: String, packageName: String, className: String, density: Int): Drawable? {
        val p = ensureLoaded(pack) ?: return null
        val name = p.map["$packageName/$className"]
            ?: p.map.entries.firstOrNull { it.key.startsWith("$packageName/") }?.value
            ?: return null
        val id = p.res.getIdentifier(name, "drawable", pack)
        if (id == 0) return null
        return try {
            p.res.getDrawableForDensity(id, density, null)
        } catch (t: Throwable) {
            null
        }
    }

    fun invalidate() {
        loaded = null
    }
}
