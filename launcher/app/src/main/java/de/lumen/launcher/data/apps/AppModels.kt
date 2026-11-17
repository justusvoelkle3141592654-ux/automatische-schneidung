package de.lumen.launcher.data.apps

import android.os.UserHandle
import androidx.compose.runtime.Immutable

/**
 * Stable identifier of a launchable activity for a given user profile.
 * Format: "package/class@userSerial". Used for persistence (layout, usage, hidden apps).
 */
@JvmInline
@kotlinx.serialization.Serializable
value class AppKey(val value: String) {
    val packageName: String get() = value.substringBefore('/')
    val className: String get() = value.substringAfter('/').substringBefore('@')
    val userSerial: Long get() = value.substringAfterLast('@', "0").toLongOrNull() ?: 0L

    companion object {
        fun of(packageName: String, className: String, userSerial: Long) =
            AppKey("$packageName/$className@$userSerial")
    }
}

@Immutable
data class AppInfo(
    val key: AppKey,
    val label: String,
    val packageName: String,
    val className: String,
    val user: UserHandle,
    val userSerial: Long,
    val isWorkProfile: Boolean,
    val systemCategory: AppCategory,
    val firstInstallTime: Long,
    val isSystemApp: Boolean,
) {
    /** Lower-cased, accent-free label used for search matching. */
    val searchLabel: String = normalizeForSearch(label)
}

/** App Library categories (modelled after the iOS App Library groups). */
enum class AppCategory(val title: String) {
    SOCIAL("Soziale Netze"),
    COMMUNICATION("Kommunikation"),
    PRODUCTIVITY("Produktivität & Finanzen"),
    UTILITIES("Dienstprogramme"),
    CREATIVITY("Kreativität"),
    ENTERTAINMENT("Unterhaltung"),
    READING("Information & Lesen"),
    GAMES("Spiele"),
    SHOPPING("Shopping & Essen"),
    TRAVEL("Reisen"),
    HEALTH("Gesundheit & Fitness"),
    EDUCATION("Bildung"),
    OTHER("Andere"),
}

private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")

fun normalizeForSearch(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(diacritics, "")
        .replace("ß", "ss")
