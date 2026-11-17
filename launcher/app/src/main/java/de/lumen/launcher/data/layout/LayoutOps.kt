package de.lumen.launcher.data.layout

import de.lumen.launcher.data.apps.AppKey

/** What is being dragged. */
sealed interface DragPayload {
    /** An item that already lives on a page or in the dock. */
    data class Existing(val itemId: Long) : DragPayload
    /** An app dragged out of an open folder. */
    data class FromFolder(val folderId: Long, val app: AppKey) : DragPayload
    /** An app dragged from the App Library / search onto the home screen. */
    data class FromLibrary(val app: AppKey) : DragPayload
}

/** Where it is dropped. */
sealed interface DropTarget {
    data class PageCell(val page: Int, val cell: Cell) : DropTarget
    data class Dock(val index: Int) : DropTarget
    /** Dropped onto another item: creates a folder or adds to an existing one. */
    data class OntoItem(val itemId: Long) : DropTarget
}

/**
 * Pure transformations of [HomeLayout]. Every function returns a new layout; invalid operations
 * return the input unchanged, so the UI can never end in a broken state.
 */
object LayoutOps {

    data class Location(val page: Int?, val inDock: Boolean)

    fun locate(layout: HomeLayout, itemId: Long): Location? {
        layout.pages.forEachIndexed { i, p -> if (p.items.any { it.id == itemId }) return Location(i, false) }
        if (layout.dock.any { it.id == itemId }) return Location(null, true)
        return null
    }

    fun findItem(layout: HomeLayout, itemId: Long): HomeItem? = layout.allItems().firstOrNull { it.id == itemId }

    private fun HomeLayout.mapPage(index: Int, f: (List<HomeItem>) -> List<HomeItem>) =
        copy(pages = pages.mapIndexed { i, p -> if (i == index) p.copy(items = f(p.items)) else p })

    private fun reindexDock(dock: List<HomeItem>) = dock.mapIndexed { i, it -> it.moved(Cell(i, 0)) }

    /** Removes an item from wherever it is. */
    fun removeItem(layout: HomeLayout, itemId: Long): HomeLayout = layout.copy(
        pages = layout.pages.map { p -> p.copy(items = p.items.filterNot { it.id == itemId }) },
        dock = reindexDock(layout.dock.filterNot { it.id == itemId }),
    )

    /** Folders with a single app turn back into that app; empty folders disappear (iOS behaviour). */
    fun normalizeFolders(layout: HomeLayout): HomeLayout {
        fun fix(item: HomeItem): HomeItem? = when {
            item is FolderItem && item.apps.isEmpty() -> null
            item is FolderItem && item.apps.size == 1 -> AppItem(item.id, item.cell, item.apps.first())
            else -> item
        }
        return layout.copy(
            pages = layout.pages.map { p -> p.copy(items = p.items.mapNotNull(::fix)) },
            dock = layout.dock.mapNotNull(::fix),
        )
    }

    /** Removes uninstalled apps everywhere (pages, folders, dock). */
    fun removeApps(layout: HomeLayout, gone: Set<AppKey>): HomeLayout {
        if (gone.isEmpty()) return layout
        fun keep(item: HomeItem): HomeItem? = when (item) {
            is AppItem -> if (item.app in gone) null else item
            is FolderItem -> item.copy(apps = item.apps.filterNot { it in gone })
            else -> item
        }
        return normalizeFolders(
            layout.copy(
                pages = layout.pages.map { p -> p.copy(items = p.items.mapNotNull(::keep)) },
                dock = reindexDock(layout.dock.mapNotNull(::keep)),
            )
        )
    }

    /** Places apps in the first free cells, starting at the last page, adding pages if needed. */
    fun addApps(layout: HomeLayout, apps: List<AppKey>, cols: Int, rows: Int, maxPages: Int = 15): HomeLayout {
        var l = layout
        for (app in apps) {
            if (app in l.placedApps()) continue
            var placed = false
            val startPage = (l.pages.size - 1).coerceAtLeast(0)
            for (pi in startPage until l.pages.size) {
                val cell = GridMath.findFree(l.pages[pi].items, 1, 1, cols, rows) ?: continue
                l = l.mapPage(pi) { it + AppItem(l.nextId, cell, app) }.copy(nextId = l.nextId + 1)
                placed = true
                break
            }
            if (!placed && l.pages.size < maxPages) {
                val page = HomePage(l.nextId + 1, listOf(AppItem(l.nextId, Cell(0, 0), app)))
                l = l.copy(pages = l.pages + page, nextId = l.nextId + 2)
            }
        }
        return l
    }

    fun addPage(layout: HomeLayout): HomeLayout =
        layout.copy(pages = layout.pages + HomePage(layout.nextId), nextId = layout.nextId + 1)

    /** Removes a page; its items move to the library (apps stay installed). Never removes the last page. */
    fun removePage(layout: HomeLayout, index: Int): HomeLayout {
        if (layout.pages.size <= 1 || index !in layout.pages.indices) return layout
        return layout.copy(pages = layout.pages.filterIndexed { i, _ -> i != index })
    }

    fun movePage(layout: HomeLayout, from: Int, to: Int): HomeLayout {
        if (from !in layout.pages.indices || to !in layout.pages.indices) return layout
        val list = layout.pages.toMutableList()
        val p = list.removeAt(from)
        list.add(to, p)
        return layout.copy(pages = list)
    }

    fun removeEmptyPages(layout: HomeLayout): HomeLayout {
        val nonEmpty = layout.pages.filter { it.items.isNotEmpty() }
        return layout.copy(pages = nonEmpty.ifEmpty { listOf(layout.pages.first().copy(items = emptyList())) })
    }

    fun renameFolder(layout: HomeLayout, folderId: Long, name: String): HomeLayout {
        fun r(i: HomeItem) = if (i is FolderItem && i.id == folderId) i.copy(name = name.ifBlank { i.name }) else i
        return layout.copy(pages = layout.pages.map { p -> p.copy(items = p.items.map(::r)) }, dock = layout.dock.map(::r))
    }

    fun reorderInFolder(layout: HomeLayout, folderId: Long, from: Int, to: Int): HomeLayout {
        fun r(i: HomeItem): HomeItem {
            if (i !is FolderItem || i.id != folderId || from !in i.apps.indices) return i
            val list = i.apps.toMutableList()
            val a = list.removeAt(from)
            list.add(to.coerceIn(0, list.size), a)
            return i.copy(apps = list)
        }
        return layout.copy(pages = layout.pages.map { p -> p.copy(items = p.items.map(::r)) }, dock = layout.dock.map(::r))
    }

    fun addWidget(layout: HomeLayout, page: Int, kind: WidgetKind, size: WidgetSize, cols: Int, rows: Int): Pair<HomeLayout, Boolean> {
        if (page !in layout.pages.indices) return layout to false
        val cell = GridMath.findFree(layout.pages[page].items, size.w.coerceAtMost(cols), size.h, cols, rows)
            ?: return layout to false
        val item = WidgetItem(layout.nextId, cell, kind, size)
        return layout.mapPage(page) { it + item }.copy(nextId = layout.nextId + 1) to true
    }

    fun addNativeWidget(layout: HomeLayout, page: Int, appWidgetId: Int, provider: String, w: Int, h: Int, cols: Int, rows: Int): Pair<HomeLayout, Boolean> {
        if (page !in layout.pages.indices) return layout to false
        val cell = GridMath.findFree(layout.pages[page].items, w.coerceIn(1, cols), h.coerceIn(1, rows), cols, rows)
            ?: return layout to false
        val item = NativeWidgetItem(layout.nextId, cell, appWidgetId, provider)
        return layout.mapPage(page) { it + item }.copy(nextId = layout.nextId + 1) to true
    }

    /** Changes widget size if the new footprint fits at the same origin (or anywhere on the page). */
    fun resizeWidget(layout: HomeLayout, itemId: Long, size: WidgetSize, cols: Int, rows: Int): HomeLayout {
        val loc = locate(layout, itemId) ?: return layout
        val page = loc.page ?: return layout
        val item = layout.pages[page].items.firstOrNull { it.id == itemId } as? WidgetItem ?: return layout
        val others = layout.pages[page].items.filterNot { it.id == itemId }
        val want = Cell(item.cell.x, item.cell.y, size.w.coerceAtMost(cols), size.h)
        val target = when {
            GridMath.isFree(others, want, cols, rows) -> want
            else -> GridMath.findNearestFree(others, want, cols, rows) ?: return layout
        }
        return layout.mapPage(page) { list -> list.map { if (it.id == itemId) item.copy(cell = target, size = size) else it } }
    }

    fun updateWidgetConfig(layout: HomeLayout, itemId: Long, config: Map<String, String>): HomeLayout {
        fun r(i: HomeItem) = if (i is WidgetItem && i.id == itemId) i.copy(config = i.config + config) else i
        return layout.copy(pages = layout.pages.map { p -> p.copy(items = p.items.map(::r)) })
    }

    // ------------------------------------------------------------------ drag & drop

    /**
     * Applies a drop. Returns the unchanged layout when the drop is impossible (e.g. full page),
     * so the dragged icon simply flies back.
     */
    fun drop(
        layout: HomeLayout,
        payload: DragPayload,
        target: DropTarget,
        cols: Int,
        rows: Int,
        maxDock: Int,
        folderName: (List<AppKey>) -> String,
    ): HomeLayout {
        // 1. Detach the payload into a free-floating item.
        var l = layout
        val floating: HomeItem = when (payload) {
            is DragPayload.Existing -> {
                val item = findItem(l, payload.itemId) ?: return layout
                // Dropping an item onto itself or its own cell: nothing to do.
                if (target is DropTarget.OntoItem && target.itemId == payload.itemId) return layout
                l = removeItem(l, payload.itemId)
                item
            }
            is DragPayload.FromFolder -> {
                l = removeFromFolder(l, payload.folderId, payload.app)
                AppItem(l.nextId, Cell(0, 0), payload.app).also { l = l.copy(nextId = l.nextId + 1) }
            }
            is DragPayload.FromLibrary -> {
                AppItem(l.nextId, Cell(0, 0), payload.app).also { l = l.copy(nextId = l.nextId + 1) }
            }
        }

        // 2. Attach at the target.
        val result: HomeLayout? = when (target) {
            is DropTarget.PageCell -> placeOnPage(l, floating, target.page, target.cell, cols, rows)
            is DropTarget.Dock -> placeInDock(l, floating, target.index, maxDock)
            is DropTarget.OntoItem -> mergeOnto(l, floating, target.itemId, folderName)
                ?: run {
                    val loc = locate(l, target.itemId)
                    val ti = findItem(l, target.itemId)
                    when {
                        loc?.page != null && ti != null -> placeOnPage(l, floating, loc.page, ti.cell, cols, rows)
                        loc?.inDock == true && ti != null -> placeInDock(l, floating, ti.cell.x, maxDock)
                        else -> null
                    }
                }
        }
        return result?.let(::normalizeFolders) ?: layout
    }

    private fun removeFromFolder(layout: HomeLayout, folderId: Long, app: AppKey): HomeLayout {
        fun r(i: HomeItem) = if (i is FolderItem && i.id == folderId) i.copy(apps = i.apps - app) else i
        return normalizeFolders(layout.copy(pages = layout.pages.map { p -> p.copy(items = p.items.map(::r)) }, dock = layout.dock.map(::r)))
    }

    private fun placeOnPage(l: HomeLayout, item: HomeItem, page: Int, wanted: Cell, cols: Int, rows: Int): HomeLayout? {
        if (page !in l.pages.indices) return null
        val w = item.cell.w.coerceAtMost(cols)
        val h = item.cell.h.coerceAtMost(rows)
        val want = Cell(wanted.x.coerceIn(0, cols - w), wanted.y.coerceIn(0, rows - h), w, h)
        val items = l.pages[page].items
        val cell = if (GridMath.isFree(items, want, cols, rows)) want else GridMath.findNearestFree(items, want, cols, rows) ?: return null
        return l.mapPage(page) { it + item.moved(cell) }
    }

    private fun placeInDock(l: HomeLayout, item: HomeItem, index: Int, maxDock: Int): HomeLayout? {
        if (item !is AppItem && item !is FolderItem) return null
        if (l.dock.size >= maxDock) return null
        val list = l.dock.toMutableList()
        list.add(index.coerceIn(0, list.size), item)
        return l.copy(dock = reindexDock(list))
    }

    private fun mergeOnto(l: HomeLayout, item: HomeItem, targetId: Long, folderName: (List<AppKey>) -> String): HomeLayout? {
        val apps: List<AppKey> = when (item) {
            is AppItem -> listOf(item.app)
            is FolderItem -> item.apps
            else -> return null
        }
        val target = findItem(l, targetId) ?: return null
        val replacement: HomeItem = when (target) {
            is AppItem -> {
                val all = listOf(target.app) + apps
                FolderItem(target.id, target.cell, folderName(all), all.distinct())
            }
            is FolderItem -> target.copy(apps = (target.apps + apps).distinct())
            else -> return null
        }
        return l.copy(
            pages = l.pages.map { p -> p.copy(items = p.items.map { if (it.id == targetId) replacement else it }) },
            dock = l.dock.map { if (it.id == targetId) replacement else it },
        )
    }

    // ------------------------------------------------------------------ defaults

    /**
     * Builds the first-run layout: a clock widget, calendar + weather widgets and favourite apps on
     * page 1, the dock with phone/messages/browser/camera, remaining apps on following pages.
     */
    fun buildDefault(
        dock: List<AppKey>,
        favourites: List<AppKey>,
        others: List<AppKey>,
        cols: Int,
        rows: Int,
        maxPages: Int = 3,
    ): HomeLayout {
        var id = 1L
        val first = mutableListOf<HomeItem>()
        first += WidgetItem(id++, Cell(0, 0, cols.coerceAtMost(4), 2), WidgetKind.CLOCK, WidgetSize.MEDIUM)
        if (cols >= 4 && rows >= 6) {
            first += WidgetItem(id++, Cell(0, 2, 2, 2), WidgetKind.CALENDAR, WidgetSize.SMALL)
            first += WidgetItem(id++, Cell(2, 2, 2, 2), WidgetKind.WEATHER, WidgetSize.SMALL)
        }
        val queue = ArrayDeque(favourites.distinct().filterNot { it in dock })
        while (queue.isNotEmpty()) {
            val cell = GridMath.findFree(first, 1, 1, cols, rows) ?: break
            first += AppItem(id++, cell, queue.removeFirst())
        }
        val pages = mutableListOf(HomePage(id++, first))
        val onFirst = first.filterIsInstance<AppItem>().map { it.app }.toSet()
        val rest = ArrayDeque((queue + others).distinct().filterNot { it in dock || it in onFirst })
        while (rest.isNotEmpty() && pages.size < maxPages) {
            val items = mutableListOf<HomeItem>()
            while (rest.isNotEmpty()) {
                val cell = GridMath.findFree(items, 1, 1, cols, rows) ?: break
                items += AppItem(id++, cell, rest.removeFirst())
            }
            pages += HomePage(id++, items)
        }
        val dockItems = dock.distinct().take(4).mapIndexed { i, k -> AppItem(id++, Cell(i, 0), k) }
        return HomeLayout(pages = pages, dock = dockItems, nextId = id + 1, initialized = true)
    }
}
