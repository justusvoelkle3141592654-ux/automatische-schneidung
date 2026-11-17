package de.lumen.launcher.data.apps

import kotlinx.serialization.Serializable

@Serializable
data class UsageEntry(val launches: Int = 0, val lastLaunch: Long = 0L)

@Serializable
data class CustomCategory(val id: Long, val name: String, val apps: List<AppKey> = emptyList())

/** Everything the App Library needs to remember: usage, hidden apps, own categories. */
@Serializable
data class LibraryState(
    val usage: Map<String, UsageEntry> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val customCategories: List<CustomCategory> = emptyList(),
    /** Manual category override per app key. */
    val categoryOverrides: Map<String, AppCategory> = emptyMap(),
    /** Apps already seen; used to detect newly installed apps. */
    val knownApps: Set<String> = emptySet(),
)

object UsageRanking {
    /**
     * Frecency score: launches weighted by recency (half-life of 3 days).
     * Used for "Vorschläge" (suggestions) in the App Library and search.
     */
    fun score(entry: UsageEntry, now: Long): Double {
        if (entry.launches == 0) return 0.0
        val ageHours = ((now - entry.lastLaunch).coerceAtLeast(0L)) / 3_600_000.0
        val recency = Math.pow(0.5, ageHours / 72.0)
        return entry.launches * (0.35 + 0.65 * recency)
    }

    fun suggestions(usage: Map<String, UsageEntry>, now: Long, limit: Int): List<String> =
        usage.entries
            .filter { it.value.launches > 0 }
            .sortedByDescending { score(it.value, now) }
            .take(limit)
            .map { it.key }

    fun recents(usage: Map<String, UsageEntry>, limit: Int): List<String> =
        usage.entries
            .filter { it.value.lastLaunch > 0 }
            .sortedByDescending { it.value.lastLaunch }
            .take(limit)
            .map { it.key }
}
