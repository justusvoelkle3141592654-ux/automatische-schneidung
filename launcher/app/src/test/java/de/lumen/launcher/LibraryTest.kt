package de.lumen.launcher

import de.lumen.launcher.data.apps.AppCategory
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.Calculator
import de.lumen.launcher.data.apps.CustomCategory
import de.lumen.launcher.data.apps.LibraryModel
import de.lumen.launcher.data.apps.LibraryState
import de.lumen.launcher.data.apps.SearchEngine
import de.lumen.launcher.data.apps.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryTest {
    private fun app(label: String, pkg: String, cat: AppCategory, installed: Long = 0) = AppInfo(
        AppKey.of(pkg, "$pkg.Main", 0), label, pkg, "$pkg.Main", android.os.Process.myUserHandle(), 0, false, cat, installed, false,
    )

    private val apps = listOf(
        app("Google Maps", "com.google.maps", AppCategory.TRAVEL, 5),
        app("Mail", "com.mail", AppCategory.COMMUNICATION, 1),
        app("Messenger", "com.msg", AppCategory.COMMUNICATION, 9),
        app("Ärztefinder", "de.arzt", AppCategory.HEALTH, 2),
        app("Kamera", "com.cam", AppCategory.CREATIVITY, 3),
        app("1Password", "com.pw", AppCategory.UTILITIES, 4),
    )

    @Test fun search_prefixBeatsContains() {
        val r = SearchEngine.search(apps, "ma", emptyMap(), 0)
        assertEquals("Mail", r.first().label)
        assertTrue(r.any { it.label == "Google Maps" })
    }

    @Test fun search_initials() {
        assertEquals("Google Maps", SearchEngine.search(apps, "gm", emptyMap(), 0).first().label)
    }

    @Test fun search_accentInsensitive() {
        assertEquals("Ärztefinder", SearchEngine.search(apps, "arzt", emptyMap(), 0).first().label)
    }

    @Test fun search_usageBoost() {
        val now = 1_700_000_000_000L
        val usage = mapOf(apps[2].key.value to UsageEntry(50, now))
        assertEquals("Messenger", SearchEngine.search(apps, "m", usage, now).first().label)
    }

    @Test fun library_hidesHiddenApps_andBuildsCategories() {
        val state = LibraryState(hidden = setOf(apps[4].key.value))
        val cats = LibraryModel.build(apps, state, 0)
        assertEquals(LibraryModel.SUGGESTIONS, cats.first().id)
        assertFalse(cats.flatMap { it.apps }.any { it.label == "Kamera" })
        assertTrue(cats.any { it.title == AppCategory.COMMUNICATION.title && it.apps.size == 2 })
        assertEquals("Messenger", cats.first { it.id == LibraryModel.RECENT }.apps.first().label)
    }

    @Test fun library_customCategoryAndOverride() {
        val state = LibraryState(
            customCategories = listOf(CustomCategory(1, "Arbeit", listOf(apps[1].key))),
            categoryOverrides = mapOf(apps[0].key.value to AppCategory.UTILITIES),
        )
        val cats = LibraryModel.build(apps, state, 0)
        val work = cats.first { it.custom }
        assertEquals(listOf("Mail"), work.apps.map { it.label })
        assertTrue(cats.first { it.title == AppCategory.UTILITIES.title }.apps.any { it.label == "Google Maps" })
        assertFalse(cats.first { it.title == AppCategory.COMMUNICATION.title }.apps.any { it.label == "Mail" })
    }

    @Test fun alphabetical_groupsUmlautsAndDigits() {
        val groups = LibraryModel.alphabetical(apps).toMap()
        assertTrue(groups.getValue("A").any { it.label == "Ärztefinder" })
        assertTrue(groups.getValue("#").any { it.label == "1Password" })
        assertEquals("#", LibraryModel.alphabetical(apps).last().first)
    }

    @Test fun calculator() {
        assertEquals(16.0, Calculator.evaluate("12+4")!!, 1e-9)
        assertEquals(14.0, Calculator.evaluate("2+3*4")!!, 1e-9)
        assertEquals(1024.0, Calculator.evaluate("2^10")!!, 1e-9)
        assertEquals(2.5, Calculator.evaluate("5 / 2")!!, 1e-9)
        assertEquals(-3.0, Calculator.evaluate("-(1+2)")!!, 1e-9)
        assertNull(Calculator.evaluate("Mail"))
        assertNull(Calculator.evaluate("12"))
        assertNull(Calculator.evaluate("1/0"))
        assertEquals("1.024", Calculator.format(1024.0))
        assertEquals("2,5", Calculator.format(2.5))
    }
}
