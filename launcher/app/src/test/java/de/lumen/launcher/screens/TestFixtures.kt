package de.lumen.launcher.screens

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import de.lumen.launcher.AppGraph
import de.lumen.launcher.data.apps.AppCategory
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.Cell
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.HomePage
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize

/** Fake apps with generated adaptive icons, so screenshots show a realistic home screen. */
object TestFixtures {
    data class FakeApp(val label: String, val c1: Int, val c2: Int, val glyph: String, val category: AppCategory)

    val fakeApps = listOf(
        FakeApp("Telefon", 0xFF34C759.toInt(), 0xFF248A3D.toInt(), "☎", AppCategory.COMMUNICATION),
        FakeApp("Nachrichten", 0xFF5EF06F.toInt(), 0xFF2DBE3F.toInt(), "✉", AppCategory.COMMUNICATION),
        FakeApp("Browser", 0xFF4FA3FF.toInt(), 0xFF1060E0.toInt(), "◎", AppCategory.UTILITIES),
        FakeApp("Kamera", 0xFF8E8E93.toInt(), 0xFF3A3A3C.toInt(), "◉", AppCategory.CREATIVITY),
        FakeApp("Fotos", 0xFFFFD60A.toInt(), 0xFFFF6B3D.toInt(), "✿", AppCategory.CREATIVITY),
        FakeApp("Kalender", 0xFFFFFFFF.toInt(), 0xFFEDEDED.toInt(), "17", AppCategory.PRODUCTIVITY),
        FakeApp("Mail", 0xFF3FA9FF.toInt(), 0xFF0A6BFF.toInt(), "@", AppCategory.COMMUNICATION),
        FakeApp("Karten", 0xFF7BE0A0.toInt(), 0xFF3C9DFF.toInt(), "➤", AppCategory.TRAVEL),
        FakeApp("Uhr", 0xFF1C1C1E.toInt(), 0xFF000000.toInt(), "◷", AppCategory.UTILITIES),
        FakeApp("Musik", 0xFFFF5E7E.toInt(), 0xFFFA2D48.toInt(), "♪", AppCategory.ENTERTAINMENT),
        FakeApp("Notizen", 0xFFFFE680.toInt(), 0xFFFFC93C.toInt(), "✎", AppCategory.PRODUCTIVITY),
        FakeApp("Einstellungen", 0xFFAEAEB2.toInt(), 0xFF636366.toInt(), "⚙", AppCategory.UTILITIES),
        FakeApp("Wetter", 0xFF5AC8FA.toInt(), 0xFF1E6FD9.toInt(), "☀", AppCategory.UTILITIES),
        FakeApp("Rechner", 0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt(), "±", AppCategory.UTILITIES),
        FakeApp("Store", 0xFF2FC2FF.toInt(), 0xFF1773F5.toInt(), "A", AppCategory.UTILITIES),
        FakeApp("Podcasts", 0xFFC86DFF.toInt(), 0xFF8E2DE2.toInt(), "◍", AppCategory.ENTERTAINMENT),
        FakeApp("Bücher", 0xFFFFA43C.toInt(), 0xFFFF7A00.toInt(), "▤", AppCategory.READING),
        FakeApp("Fitness", 0xFF1C1C1E.toInt(), 0xFF000000.toInt(), "◎", AppCategory.HEALTH),
        FakeApp("Dateien", 0xFF4FA3FF.toInt(), 0xFF2F7CF6.toInt(), "▭", AppCategory.UTILITIES),
        FakeApp("Video", 0xFF2C2C2E.toInt(), 0xFF000000.toInt(), "▶", AppCategory.ENTERTAINMENT),
        FakeApp("Banking", 0xFF30D158.toInt(), 0xFF0E7C3A.toInt(), "€", AppCategory.PRODUCTIVITY),
        FakeApp("Sprachen", 0xFF7CDE3E.toInt(), 0xFF46A302.toInt(), "Ä", AppCategory.EDUCATION),
        FakeApp("Shop", 0xFFFF9F0A.toInt(), 0xFFFF6A00.toInt(), "◆", AppCategory.SHOPPING),
        FakeApp("Spiel", 0xFFBF5AF2.toInt(), 0xFF5E5CE6.toInt(), "★", AppCategory.GAMES),
    )

    fun key(i: Int) = AppKey.of("com.example.app$i", "com.example.app$i.Main", 0)

    fun apps(): List<AppInfo> = fakeApps.mapIndexed { i, f ->
        AppInfo(
            key = key(i), label = f.label, packageName = "com.example.app$i", className = "com.example.app$i.Main",
            user = Process.myUserHandle(), userSerial = 0, isWorkProfile = false, systemCategory = f.category,
            firstInstallTime = i.toLong(), isSystemApp = false,
        )
    }.sortedBy { it.label }

    fun install(graph: AppGraph) {
        val byKey = fakeApps.mapIndexed { i, f -> key(i).value to f }.toMap()
        graph.icons.testIconProvider = { app -> byKey[app.key.value]?.let { icon(it) } }
        graph.settings.update { it.copy(addNewAppsToHome = false) }
        graph.library.update { it.copy(knownApps = fakeApps.indices.map { i -> key(i).value }.toSet()) }
        graph.layout.set(layout())
        graph.apps.setAppsForTest(apps())
    }

    fun layout(): HomeLayout {
        var id = 1L
        val page1 = mutableListOf<de.lumen.launcher.data.layout.HomeItem>(
            WidgetItem(id++, Cell(0, 0, 4, 2), WidgetKind.CLOCK, WidgetSize.MEDIUM),
            WidgetItem(id++, Cell(0, 2, 2, 2), WidgetKind.DATE, WidgetSize.SMALL),
        )
        page1 += FolderItem(id++, Cell(2, 2), "Dienstprogramme", listOf(key(11), key(13), key(18), key(12)))
        page1 += AppItem(id++, Cell(3, 2), key(4))
        page1 += AppItem(id++, Cell(2, 3), key(5))
        page1 += AppItem(id++, Cell(3, 3), key(6))
        val row = listOf(7, 8, 9, 10, 14, 15, 16, 17)
        row.forEachIndexed { i, k -> page1 += AppItem(id++, Cell(i % 4, 4 + i / 4), key(k)) }
        val page2 = (19 until 24).mapIndexed { i, k -> AppItem(id++, Cell(i % 4, i / 4), key(k)) }
        return HomeLayout(
            pages = listOf(HomePage(id++, page1), HomePage(id++, page2)),
            dock = listOf(0, 1, 2, 3).mapIndexed { i, k -> AppItem(id++, Cell(i, 0), key(k)) },
            nextId = id + 10,
            initialized = true,
        )
    }

    private fun icon(f: FakeApp): Drawable = AdaptiveIconDrawable(GradientDrawableBg(f.c1, f.c2), GlyphDrawable(f.glyph, f.c1))

    private class GradientDrawableBg(val c1: Int, val c2: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), c1, c2, Shader.TileMode.CLAMP)
            canvas.drawRect(b, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("") override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class GlyphDrawable(val glyph: String, val bg: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val lum = (0.3 * ((bg shr 16) and 0xFF) + 0.59 * ((bg shr 8) and 0xFF) + 0.11 * (bg and 0xFF))
            p.color = if (lum > 200) 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt()
            p.textSize = b.height() * 0.30f
            canvas.drawText(glyph, b.exactCenterX(), b.exactCenterY() + p.textSize * 0.36f, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
