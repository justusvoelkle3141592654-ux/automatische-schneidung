package de.lumen.launcher

import de.lumen.launcher.core.LumenJson
import de.lumen.launcher.data.apps.AppCategorizer
import de.lumen.launcher.data.apps.AppCategory
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.UsageEntry
import de.lumen.launcher.data.apps.UsageRanking
import de.lumen.launcher.data.apps.normalizeForSearch
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.Cell
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.GridMath
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.HomePage
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize
import de.lumen.launcher.data.settings.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    private fun app(id: Long, x: Int, y: Int) = AppItem(id, Cell(x, y), AppKey("p$id/c@0"))

    @Test fun categorizer_usesSystemCategoryFirst() {
        assertEquals(AppCategory.GAMES, AppCategorizer.categorize("com.foo.whatsapp", "x", AppCategorizer.CATEGORY_GAME))
    }

    @Test fun categorizer_keywords() {
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("com.whatsapp", "WhatsApp", -1))
        assertEquals(AppCategory.TRAVEL, AppCategorizer.categorize("com.google.android.apps.maps", "Maps", -1))
        assertEquals(AppCategory.UTILITIES, AppCategorizer.categorize("com.android.settings", "Einstellungen", -1))
        assertEquals(AppCategory.OTHER, AppCategorizer.categorize("org.qwerty.zzz", "Zzz", -1))
    }

    @Test fun search_normalization() {
        assertEquals("strasse uber", normalizeForSearch("Straße Über"))
    }

    @Test fun appKey_parts() {
        val k = AppKey.of("com.a", "com.a.Main", 10)
        assertEquals("com.a", k.packageName)
        assertEquals("com.a.Main", k.className)
        assertEquals(10L, k.userSerial)
    }

    @Test fun grid_findFree_skipsOccupied() {
        val items = listOf(app(1, 0, 0), app(2, 1, 0))
        assertEquals(Cell(2, 0), GridMath.findFree(items, 1, 1, 4, 6))
        val widget = GridMath.findFree(items, 2, 2, 4, 6)
        assertEquals(Cell(2, 0, 2, 2), widget)
    }

    @Test fun grid_full_returnsNull() {
        val items = (0 until 4).flatMap { y -> (0 until 4).map { x -> app((y * 4 + x).toLong(), x, y) } }
        assertNull(GridMath.findFree(items, 1, 1, 4, 4))
    }

    @Test fun grid_nearestFree() {
        val items = listOf(app(1, 1, 1))
        val c = GridMath.findNearestFree(items, Cell(1, 1), 4, 6)
        assertNotNull(c)
        assertTrue(c != Cell(1, 1))
        assertEquals(1, Math.abs(c!!.x - 1) + Math.abs(c.y - 1))
    }

    @Test fun grid_reflowSmallerGrid() {
        val items = listOf(app(1, 4, 5), WidgetItem(2, Cell(0, 0, 4, 2), WidgetKind.CLOCK, WidgetSize.MEDIUM))
        val r = GridMath.reflow(items, 4, 5)
        assertTrue(r.overflow.isEmpty())
        r.placed.forEach { assertTrue(GridMath.fits(it.cell, 4, 5)) }
        assertFalse(r.placed[0].cell.overlaps(r.placed[1].cell))
    }

    @Test fun grid_arrangeLandscape_keepsOrderAndFits() {
        val items = (0 until 8).map { app(it.toLong(), it % 4, it / 4) }
        val arranged = GridMath.arrangeForGrid(items, 6, 4)
        assertEquals(8, arranged.size)
        assertEquals(Cell(0, 0), arranged.first { it.id == 0L }.cell)
        assertEquals(Cell(4, 0), arranged.first { it.id == 4L }.cell)
    }

    @Test fun ranking_prefersRecentFrequent() {
        val now = 1_700_000_000_000L
        val usage = mapOf(
            "old" to UsageEntry(launches = 10, lastLaunch = now - 30L * 24 * 3600 * 1000),
            "new" to UsageEntry(launches = 6, lastLaunch = now - 3600 * 1000),
            "never" to UsageEntry(),
        )
        assertEquals(listOf("new", "old"), UsageRanking.suggestions(usage, now, 5))
        assertEquals(listOf("new", "old"), UsageRanking.recents(usage, 5))
    }

    @Test fun layout_serializationRoundTrip() {
        val layout = HomeLayout(
            pages = listOf(
                HomePage(1, listOf(app(1, 0, 0), FolderItem(2, Cell(1, 0), "Tools", listOf(AppKey("a/b@0"))))),
                HomePage(2, listOf(WidgetItem(3, Cell(0, 0, 2, 2), WidgetKind.WEATHER, WidgetSize.SMALL, mapOf("city" to "Berlin")))),
            ),
            dock = listOf(app(4, 0, 0)),
            initialized = true,
        )
        val text = LumenJson.encodeToString(HomeLayout.serializer(), layout)
        assertEquals(layout, LumenJson.decodeFromString(HomeLayout.serializer(), text))
        assertEquals(setOf(AppKey("p1/c@0"), AppKey("a/b@0"), AppKey("p4/c@0")), layout.placedApps())
    }

    @Test fun settings_toleratesUnknownAndMissingKeys() {
        val s = LumenJson.decodeFromString(LauncherSettings.serializer(), """{"iconSizeDp":70,"futureKey":true}""")
        assertEquals(70f, s.iconSizeDp)
        assertEquals(4, s.gridColumns)
    }
}
