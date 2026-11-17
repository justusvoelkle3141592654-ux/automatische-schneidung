package de.lumen.launcher

import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.Cell
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.DropTarget
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.HomePage
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetKind
import de.lumen.launcher.data.layout.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutOpsTest {
    private fun k(n: String) = AppKey("$n/$n.Main@0")
    private val name: (List<AppKey>) -> String = { "Ordner" }

    private fun base() = HomeLayout(
        pages = listOf(
            HomePage(1, listOf(AppItem(10, Cell(0, 0), k("a")), AppItem(11, Cell(1, 0), k("b")))),
            HomePage(2, listOf(AppItem(12, Cell(0, 0), k("c")))),
        ),
        dock = listOf(AppItem(20, Cell(0, 0), k("d"))),
        nextId = 100,
    )

    @Test fun moveWithinPage() {
        val l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.PageCell(0, Cell(3, 4)), 4, 6, 4, name)
        assertEquals(Cell(3, 4), LayoutOps.findItem(l, 10)!!.cell)
    }

    @Test fun moveToOccupiedCell_findsNearest() {
        val l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.PageCell(0, Cell(1, 0)), 4, 6, 4, name)
        val c = LayoutOps.findItem(l, 10)!!.cell
        assertTrue(c != Cell(1, 0))
    }

    @Test fun moveAcrossPages() {
        val l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.PageCell(1, Cell(2, 2)), 4, 6, 4, name)
        assertEquals(1, LayoutOps.locate(l, 10)!!.page)
    }

    @Test fun dropOntoApp_createsFolder() {
        val l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.OntoItem(11), 4, 6, 4, name)
        val folder = LayoutOps.findItem(l, 11) as FolderItem
        assertEquals(listOf(k("b"), k("a")), folder.apps)
        assertNull(LayoutOps.findItem(l, 10))
    }

    @Test fun dragLastButOneOutOfFolder_dissolvesFolder() {
        var l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.OntoItem(11), 4, 6, 4, name)
        l = LayoutOps.drop(l, DragPayload.FromFolder(11, k("a")), DropTarget.PageCell(0, Cell(2, 2)), 4, 6, 4, name)
        assertTrue(LayoutOps.findItem(l, 11) is AppItem)
        assertEquals(setOf(k("a"), k("b"), k("c"), k("d")), l.placedApps())
    }

    @Test fun dockRespectsMax() {
        val full = base().copy(dock = (0 until 4).map { AppItem(30L + it, Cell(it, 0), k("x$it")) })
        val l = LayoutOps.drop(full, DragPayload.Existing(10), DropTarget.Dock(1), 4, 6, 4, name)
        assertSame(full, l)
    }

    @Test fun moveIntoDock_reindexes() {
        val l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.Dock(0), 4, 6, 4, name)
        assertEquals(listOf(10L, 20L), l.dock.map { it.id })
        assertEquals(listOf(0, 1), l.dock.map { it.cell.x })
    }

    @Test fun widgetCannotGoToDock() {
        val withWidget = base().copy(pages = base().pages + HomePage(3, listOf(WidgetItem(40, Cell(0, 0, 2, 2), WidgetKind.BATTERY, WidgetSize.SMALL))))
        val l = LayoutOps.drop(withWidget, DragPayload.Existing(40), DropTarget.Dock(0), 4, 6, 4, name)
        assertSame(withWidget, l)
    }

    @Test fun removeUninstalled_everywhere() {
        var l = LayoutOps.drop(base(), DragPayload.Existing(10), DropTarget.OntoItem(11), 4, 6, 4, name)
        l = LayoutOps.removeApps(l, setOf(k("a"), k("d")))
        assertEquals(setOf(k("b"), k("c")), l.placedApps())
        assertTrue(l.dock.isEmpty())
        assertTrue(LayoutOps.findItem(l, 11) is AppItem)
    }

    @Test fun addApps_fillsLastPageThenAddsPage() {
        val apps = (0 until 30).map { k("n$it") }
        val l = LayoutOps.addApps(base(), apps, 4, 6)
        assertTrue(apps.all { it in l.placedApps() })
        assertEquals(3, l.pages.size)
    }

    @Test fun fromLibrary_placesNewItem() {
        val l = LayoutOps.drop(base(), DragPayload.FromLibrary(k("z")), DropTarget.PageCell(1, Cell(3, 5)), 4, 6, 4, name)
        assertTrue(k("z") in l.placedApps())
        assertEquals(101, l.nextId)
    }

    @Test fun resizeWidget() {
        val withWidget = base().copy(pages = listOf(HomePage(3, listOf(WidgetItem(40, Cell(0, 0, 2, 2), WidgetKind.BATTERY, WidgetSize.SMALL)))))
        val l = LayoutOps.resizeWidget(withWidget, 40, WidgetSize.MEDIUM, 4, 6)
        assertEquals(Cell(0, 0, 4, 2), LayoutOps.findItem(l, 40)!!.cell)
    }

    @Test fun buildDefault_hasDockAndWidgets() {
        val l = LayoutOps.buildDefault(listOf(k("phone"), k("sms")), listOf(k("set"), k("cam")), (0 until 40).map { k("o$it") }, 4, 6)
        assertEquals(2, l.dock.size)
        assertTrue(l.pages.first().items.any { it is WidgetItem && it.kind == WidgetKind.CLOCK })
        assertTrue(l.initialized)
        val ids = l.allItems().map { it.id }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(l.nextId > ids.max())
    }

    @Test fun applyGrid_keepsEverything() {
        val items = (0 until 24).map { AppItem(200L + it, Cell(it % 4, it / 4), k("g$it")) }
        val l = HomeLayout(pages = listOf(HomePage(1, items)), nextId = 500)
        val r = LayoutOps.applyGrid(l, 3, 5)
        assertEquals(items.map { it.app }.toSet(), r.placedApps())
        r.pages.forEach { p -> p.items.forEach { assertTrue(it.cell.right <= 3 && it.cell.bottom <= 5) } }
        assertTrue(r.pages.size >= 2)
    }
}
