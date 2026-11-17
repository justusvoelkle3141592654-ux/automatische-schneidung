package de.lumen.launcher.data.layout

import de.lumen.launcher.data.apps.AppKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Rectangle on the home grid, in cells. */
@Serializable
data class Cell(val x: Int, val y: Int, val w: Int = 1, val h: Int = 1) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    fun overlaps(o: Cell) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    fun contains(cx: Int, cy: Int) = cx in x until right && cy in y until bottom
}

/** Built-in widget types rendered by the launcher itself. */
@Serializable
enum class WidgetKind(val title: String) {
    CLOCK("Uhr"),
    DATE("Datum"),
    WEATHER("Wetter"),
    CALENDAR("Kalender"),
    BATTERY("Batterie"),
    REMINDERS("Erinnerungen"),
    MUSIC("Musik"),
    SHORTCUTS("Schnellzugriffe"),
}

/** Sizes offered for widgets (iOS: small / medium / large). */
@Serializable
enum class WidgetSize(val w: Int, val h: Int, val title: String) {
    SMALL(2, 2, "Klein"),
    MEDIUM(4, 2, "Mittel"),
    LARGE(4, 4, "Groß"),
}

@Serializable
sealed interface HomeItem {
    val id: Long
    val cell: Cell
    fun moved(cell: Cell): HomeItem
}

@Serializable
@SerialName("app")
data class AppItem(override val id: Long, override val cell: Cell, val app: AppKey) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
}

@Serializable
@SerialName("folder")
data class FolderItem(
    override val id: Long,
    override val cell: Cell,
    val name: String,
    val apps: List<AppKey>,
) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
}

@Serializable
@SerialName("widget")
data class WidgetItem(
    override val id: Long,
    override val cell: Cell,
    val kind: WidgetKind,
    val size: WidgetSize,
    /** Free-form per-widget options (e.g. weather city, shortcut apps). */
    val config: Map<String, String> = emptyMap(),
) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
}

@Serializable
@SerialName("native")
data class NativeWidgetItem(
    override val id: Long,
    override val cell: Cell,
    val appWidgetId: Int,
    val provider: String,
) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
}

@Serializable
data class HomePage(val id: Long, val items: List<HomeItem> = emptyList())

@Serializable
data class HomeLayout(
    val pages: List<HomePage> = listOf(HomePage(1)),
    /** Dock entries; only [AppItem] and [FolderItem], cell.x is the slot index. */
    val dock: List<HomeItem> = emptyList(),
    val nextId: Long = 100,
    val initialized: Boolean = false,
) {
    fun allItems(): Sequence<HomeItem> = pages.asSequence().flatMap { it.items } + dock.asSequence()

    /** All app keys placed anywhere (pages, folders, dock). */
    fun placedApps(): Set<AppKey> = buildSet {
        allItems().forEach {
            when (it) {
                is AppItem -> add(it.app)
                is FolderItem -> addAll(it.apps)
                else -> Unit
            }
        }
    }
}
