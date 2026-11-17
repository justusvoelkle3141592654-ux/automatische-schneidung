package de.lumen.launcher.data.apps

/** One tile in the App Library. */
data class LibraryCategory(
    val id: String,
    val title: String,
    val apps: List<AppInfo>,
    val custom: Boolean = false,
)

/** Pure App Library logic: categories, suggestions, search. */
object LibraryModel {

    const val SUGGESTIONS = "suggestions"
    const val RECENT = "recent"

    fun visibleApps(apps: List<AppInfo>, state: LibraryState): List<AppInfo> =
        apps.filter { it.key.value !in state.hidden }

    fun categoryOf(app: AppInfo, state: LibraryState): AppCategory =
        state.categoryOverrides[app.key.value] ?: app.systemCategory

    fun build(apps: List<AppInfo>, state: LibraryState, now: Long): List<LibraryCategory> {
        val visible = visibleApps(apps, state)
        if (visible.isEmpty()) return emptyList()
        val byKey = visible.associateBy { it.key.value }
        val result = mutableListOf<LibraryCategory>()

        val suggested = UsageRanking.suggestions(state.usage, now, 8).mapNotNull { byKey[it] }
        val suggestions = if (suggested.size >= 4) suggested else (suggested + visible.filter { it !in suggested }).take(8)
        result += LibraryCategory(SUGGESTIONS, "Vorschläge", suggestions)

        val recent = visible.filter { !it.isSystemApp || it.firstInstallTime > 0 }
            .sortedByDescending { it.firstInstallTime }
            .take(8)
        if (recent.isNotEmpty()) result += LibraryCategory(RECENT, "Zuletzt hinzugefügt", recent)

        val inCustom = mutableSetOf<String>()
        for (cc in state.customCategories) {
            val list = cc.apps.mapNotNull { byKey[it.value] }
            inCustom += list.map { it.key.value }
            result += LibraryCategory("custom-${cc.id}", cc.name, list, custom = true)
        }

        val grouped = visible.filter { it.key.value !in inCustom }.groupBy { categoryOf(it, state) }
        // Order categories by how much the user uses them, then by size.
        val usageOf = { list: List<AppInfo> -> list.sumOf { UsageRanking.score(state.usage[it.key.value] ?: UsageEntry(), now) } }
        grouped.entries
            .sortedWith(compareByDescending<Map.Entry<AppCategory, List<AppInfo>>> { if (it.key == AppCategory.OTHER) -1.0 else usageOf(it.value) }
                .thenByDescending { it.value.size })
            .forEach { (cat, list) ->
                val sorted = list.sortedByDescending { UsageRanking.score(state.usage[it.key.value] ?: UsageEntry(), now) }
                result += LibraryCategory("cat-${cat.name}", cat.title, sorted)
            }
        return result
    }

    /** Groups apps by first letter for the alphabetical list (non-letters under "#"). */
    fun alphabetical(apps: List<AppInfo>): List<Pair<String, List<AppInfo>>> =
        apps.groupBy { a ->
            val c = normalizeForSearch(a.label).firstOrNull()?.uppercaseChar() ?: '#'
            if (c in 'A'..'Z') c.toString() else "#"
        }.toSortedMap(compareBy { if (it == "#") "~" else it }).map { it.key to it.value }
}

/** Ranked app search used by the App Library and the Spotlight-style search. */
object SearchEngine {

    fun score(app: AppInfo, rawQuery: String): Int {
        val q = normalizeForSearch(rawQuery.trim())
        if (q.isEmpty()) return 0
        val label = app.searchLabel
        val words = label.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        return when {
            label == q -> 1000
            label.startsWith(q) -> 800 - label.length
            words.any { it.startsWith(q) } -> 600 - label.length
            initials(words).startsWith(q) && q.length >= 2 -> 450
            label.contains(q) -> 300 - label.indexOf(q)
            app.packageName.lowercase().contains(q) && q.length >= 3 -> 150
            isSubsequence(q, label) && q.length >= 3 -> 80
            else -> 0
        }
    }

    private fun initials(words: List<String>) = words.joinToString("") { it.take(1) }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (ch in s) if (i < q.length && ch == q[i]) i++
        return i == q.length
    }

    fun search(apps: List<AppInfo>, query: String, usage: Map<String, UsageEntry>, now: Long, limit: Int = 30): List<AppInfo> =
        apps.asSequence()
            .map { it to score(it, query) }
            .filter { it.second > 0 }
            .sortedByDescending { (a, s) -> s + (UsageRanking.score(usage[a.key.value] ?: UsageEntry(), now) * 10).toInt().coerceAtMost(200) }
            .take(limit)
            .map { it.first }
            .toList()
}

/**
 * Tiny arithmetic evaluator for search ("12*3+4", "2^10", "15% von 80" is not supported on purpose).
 * Returns null for anything that is not a pure expression.
 */
object Calculator {
    fun evaluate(input: String): Double? {
        val s = input.replace(" ", "").replace(',', '.').replace('×', '*').replace('÷', '/').replace(':', '/')
        if (s.isEmpty() || s.none { it in "+-*/^%" } || s.any { it !in "0123456789.+-*/^%()" }) return null
        return try {
            val p = Parser(s)
            val v = p.expr()
            if (p.pos != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return String.format(java.util.Locale.GERMANY, "%,d", v.toLong())
        return String.format(java.util.Locale.GERMANY, "%,.6f", v).trimEnd('0').trimEnd(',')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun expr(): Double {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun term(): Double {
            var v = power()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/' || s[pos] == '%')) {
                val op = s[pos++]
                val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (pos < s.length && s[pos] == '^') { pos++; return Math.pow(b, power()) }
            return b
        }
        fun unary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -unary() }
            if (pos < s.length && s[pos] == '+') { pos++; return unary() }
            return atom()
        }
        fun atom(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = expr()
                require(pos < s.length && s[pos] == ')')
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start)
            return s.substring(start, pos).toDouble()
        }
    }
}
