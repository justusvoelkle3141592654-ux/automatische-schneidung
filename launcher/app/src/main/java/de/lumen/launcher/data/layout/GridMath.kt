package de.lumen.launcher.data.layout

/** Pure grid helpers for placing items on a page (unit tested). */
object GridMath {

    fun fits(cell: Cell, cols: Int, rows: Int) =
        cell.x >= 0 && cell.y >= 0 && cell.right <= cols && cell.bottom <= rows

    fun isFree(items: List<HomeItem>, cell: Cell, cols: Int, rows: Int, ignoreId: Long? = null): Boolean =
        fits(cell, cols, rows) && items.none { it.id != ignoreId && it.cell.overlaps(cell) }

    /** First free spot (reading order) for a w×h item, or null when the page is full. */
    fun findFree(items: List<HomeItem>, w: Int, h: Int, cols: Int, rows: Int): Cell? {
        for (y in 0..rows - h) for (x in 0..cols - w) {
            val c = Cell(x, y, w, h)
            if (isFree(items, c, cols, rows)) return c
        }
        return null
    }

    /** Free spot closest to the wanted position (used while dragging). */
    fun findNearestFree(
        items: List<HomeItem>, wanted: Cell, cols: Int, rows: Int, ignoreId: Long? = null,
    ): Cell? {
        var best: Cell? = null
        var bestDist = Int.MAX_VALUE
        for (y in 0..rows - wanted.h) for (x in 0..cols - wanted.w) {
            val c = Cell(x, y, wanted.w, wanted.h)
            if (!isFree(items, c, cols, rows, ignoreId)) continue
            val d = (x - wanted.x) * (x - wanted.x) + (y - wanted.y) * (y - wanted.y)
            if (d < bestDist) { bestDist = d; best = c }
        }
        return best
    }

    fun itemAt(items: List<HomeItem>, cx: Int, cy: Int): HomeItem? = items.firstOrNull { it.cell.contains(cx, cy) }

    /**
     * Clamps items into a (possibly smaller) grid. Items that no longer fit are moved to the
     * nearest free cell; if none is free they are returned in [Result.overflow].
     */
    data class Result(val placed: List<HomeItem>, val overflow: List<HomeItem>)

    fun reflow(items: List<HomeItem>, cols: Int, rows: Int): Result {
        val placed = mutableListOf<HomeItem>()
        val overflow = mutableListOf<HomeItem>()
        // Larger items first so widgets keep their place.
        for (item in items.sortedByDescending { it.cell.w * it.cell.h }) {
            val c = item.cell
            if (c.w > cols || c.h > rows) { overflow += item; continue }
            val target = if (isFree(placed, c, cols, rows)) c else findNearestFree(placed, c, cols, rows)
            if (target != null) placed += item.moved(target) else overflow += item
        }
        return Result(placed, overflow)
    }

    /**
     * Display-only arrangement for a grid with different dimensions (e.g. landscape):
     * items are laid out in reading order of their original position.
     */
    fun arrangeForGrid(items: List<HomeItem>, cols: Int, rows: Int): List<HomeItem> {
        val placed = mutableListOf<HomeItem>()
        val sorted = items.sortedWith(compareBy({ it.cell.y }, { it.cell.x }))
        for (item in sorted) {
            val w = item.cell.w.coerceAtMost(cols)
            val h = item.cell.h.coerceAtMost(rows)
            val spot = findFree(placed, w, h, cols, rows) ?: continue
            placed += item.moved(spot)
        }
        return placed
    }
}
