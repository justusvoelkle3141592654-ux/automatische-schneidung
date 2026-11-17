package de.lumen.launcher.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.lumen.launcher.AppGraph
import de.lumen.launcher.data.apps.AppCategory
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.UsageEntry
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.Cell
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.DropTarget
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.GridMath
import de.lumen.launcher.data.layout.HomeItem
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.settings.GestureAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Rect as CRect

/** Things only the Activity can do (system dialogs with results). */
interface ActivityBridge {
    fun startScreenRecording()
    fun requestPermission(permission: String, onResult: (Boolean) -> Unit)
    fun pickImage(onResult: (android.net.Uri?) -> Unit)
    fun startForResult(intent: android.content.Intent, onResult: (resultCode: Int, data: android.content.Intent?) -> Unit)
}

enum class Overlay { LIBRARY, SEARCH, CONTROL_CENTER, NOTIFICATIONS, OVERVIEW }

/** Animated, interactively draggable visibility of one overlay (0 = closed, 1 = open). */
@Stable
class OverlayAnim {
    val progress = Animatable(0f)
    var target by mutableStateOf(0f)
        internal set
    val visible by derivedStateOf { progress.value > 0.001f || target > 0f }
}

enum class Haptic { TICK, LONG_PRESS, CONFIRM }

/** What a context menu belongs to. */
sealed interface MenuTarget {
    data class App(val app: AppInfo, val item: HomeItem?, val folderId: Long? = null, val fromLibrary: Boolean = false) : MenuTarget
    data class Folder(val item: FolderItem) : MenuTarget
    data class Widget(val item: HomeItem) : MenuTarget
}

data class MenuState(val target: MenuTarget, val anchor: CRect)

data class FolderOpen(val folderId: Long, val origin: CRect)

/** What the floating drag visual shows. */
sealed interface DragVisual {
    data class App(val app: AppKey) : DragVisual
    data class Folder(val item: FolderItem) : DragVisual
    data class Widget(val item: HomeItem) : DragVisual
}

data class PendingDrag(
    val payload: DragPayload,
    val visual: DragVisual,
    val bounds: CRect,
    val down: Offset,
    val sourceItemId: Long?,
    val span: Pair<Int, Int> = 1 to 1,
)

/** Measured geometry of the home screen, used for hit-testing during drag & drop. */
class HomeGeometry {
    var gridOrigin = Offset.Zero
    var cellSize = Size.Zero
    var cols = 4
    var rows = 6
    var dockBounds = CRect.Zero
    val dockSlotMap = HashMap<Long, CRect>()
    var dockIds: List<Long> = emptyList()
    val dockSlots: List<Pair<Long, CRect>>
        get() = dockIds.mapNotNull { id -> dockSlotMap[id]?.let { id to it } }.sortedBy { it.second.left }
    var folderPanel: CRect? = null
    var screen = Size.Zero
    var gridBounds = CRect.Zero
    /** Distance from a cell's top to the icon image (for drop animations). */
    var iconTopInCell = 0f
    /** Inner margin of widgets inside their cell span. */
    var widgetInset = 0f

    fun cellRect(c: Cell): CRect = CRect(
        Offset(gridOrigin.x + c.x * cellSize.width, gridOrigin.y + c.y * cellSize.height),
        Size(c.w * cellSize.width, c.h * cellSize.height),
    )
}

@Stable
class DragState {
    var pending: PendingDrag? = null
    var active by mutableStateOf<PendingDrag?>(null)
        internal set
    /** Pointer position in root coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero
    var target by mutableStateOf<DropTarget?>(null)
    var folderHover by mutableStateOf<Long?>(null)
    var page by mutableStateOf(0)
    /** Settling animation after drop (top-left of the visual). */
    val settle = Animatable(Offset.Zero, Offset.VectorConverter)
    var settling by mutableStateOf(false)
}

@Stable
class LauncherController(
    val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    val overlays: Map<Overlay, OverlayAnim> = Overlay.entries.associateWith { OverlayAnim() }
    var settingsOpen by mutableStateOf(false)
    var widgetPickerOpen by mutableStateOf(false)
    var pageManagerOpen by mutableStateOf(false)
    var editMode by mutableStateOf(false)
    var openFolder by mutableStateOf<FolderOpen?>(null)
    var menu by mutableStateOf<MenuState?>(null)
    var isLandscape by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    val drag = DragState()
    val geometry = HomeGeometry()
    var pager: PagerState? = null

    /** Home content zoom: < 1 while an app opens, springs back when returning home. */
    val homeZoom = Animatable(1f)

    var view: View? = null
    var bridge: ActivityBridge? = null
    var haptic: (Haptic) -> Unit = {}
    var lockScreen: () -> Boolean = { false }
    var expandStatusBar: (notifications: Boolean) -> Unit = {}

    private var appLaunched = false

    // ---------------------------------------------------------------- overlays

    fun anim(o: Overlay) = overlays.getValue(o)

    fun isOpen(o: Overlay) = anim(o).target > 0f

    val anyOverlay: Boolean get() = overlays.values.any { it.target > 0f } || settingsOpen || widgetPickerOpen || pageManagerOpen

    fun open(o: Overlay) {
        menu = null
        overlays.forEach { (k, v) -> if (k != o && v.target > 0f) close(k) }
        val a = anim(o)
        a.target = 1f
        scope.launch { a.progress.animateTo(1f, spring(0.86f, 260f * motionScale())) }
    }

    fun close(o: Overlay) {
        val a = anim(o)
        a.target = 0f
        scope.launch { a.progress.animateTo(0f, spring(1f, 320f * motionScale())) }
    }

    /** Interactive drag of an overlay (from a home gesture). */
    fun dragOverlay(o: Overlay, progress: Float) {
        val a = anim(o)
        a.target = 0.5f
        scope.launch { a.progress.snapTo(progress.coerceIn(0f, 1f)) }
    }

    fun settleOverlay(o: Overlay, velocityFraction: Float) {
        val a = anim(o)
        val openIt = a.progress.value + velocityFraction * 0.25f > 0.4f
        if (openIt) open(o) else close(o)
    }

    fun closeAll(): Boolean {
        var any = false
        if (menu != null) { menu = null; any = true }
        if (openFolder != null) { openFolder = null; any = true }
        overlays.forEach { (k, v) -> if (v.target > 0f) { close(k); any = true } }
        if (widgetPickerOpen) { widgetPickerOpen = false; any = true }
        if (pageManagerOpen) { pageManagerOpen = false; any = true }
        if (settingsOpen) { settingsOpen = false; any = true }
        return any
    }

    private fun motionScale(): Float {
        val s = graph.settings.value
        return if (s.animationsEnabled) s.animationSpeed * s.animationSpeed else 50f
    }

    fun run(action: GestureAction) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.APP_LIBRARY -> open(Overlay.LIBRARY)
            GestureAction.SEARCH -> open(Overlay.SEARCH)
            GestureAction.NOTIFICATIONS -> open(Overlay.NOTIFICATIONS)
            GestureAction.CONTROL_CENTER -> open(Overlay.CONTROL_CENTER)
            GestureAction.OVERVIEW -> open(Overlay.OVERVIEW)
            GestureAction.LOCK_SCREEN -> if (!lockScreen()) showToast("Sperren erfordert die Bedienungshilfe „Lumen Sperren“ (Einstellungen › Gesten)")
            GestureAction.SETTINGS -> { closeAll(); settingsOpen = true }
        }
    }

    fun showToast(text: String) {
        toast = text
        scope.launch {
            delay(2600)
            if (toast == text) toast = null
        }
    }

    // ---------------------------------------------------------------- home / back

    /** Home button while already home: closes things, then returns to the first page. */
    fun onHomePressed() {
        if (drag.active != null) return
        if (editMode) { editMode = false; return }
        if (closeAll()) return
        pager?.let { p -> if (p.currentPage != 0) scope.launch { p.animateScrollToPage(0) } }
    }

    fun onBack(): Boolean {
        if (drag.active != null) return true
        if (closeAll()) return true
        if (editMode) { editMode = false; return true }
        return true // a launcher never finishes on back
    }

    // ---------------------------------------------------------------- apps

    fun launch(app: AppInfo, boundsInWindow: CRect?) {
        if (editMode) return
        haptic(Haptic.TICK)
        val r = boundsInWindow?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        if (graph.apps.launch(app, view, r)) {
            appLaunched = true
            recordLaunch(app.key)
            scope.launch {
                homeZoom.animateTo(0.94f, spring(1f, 500f))
            }
        } else {
            showToast("„${app.label}“ konnte nicht gestartet werden")
        }
    }

    fun recordLaunch(key: AppKey) {
        val now = System.currentTimeMillis()
        graph.library.update { st ->
            val e = st.usage[key.value] ?: UsageEntry()
            st.copy(usage = st.usage + (key.value to UsageEntry(e.launches + 1, now)))
        }
    }

    /** Called from Activity.onResume: "app closes into the home screen" zoom. */
    fun onReturnHome() {
        if (appLaunched) {
            appLaunched = false
            overlays.forEach { (k, v) -> if (v.target > 0f && k != Overlay.LIBRARY) close(k) }
            scope.launch {
                homeZoom.snapTo(0.9f)
                homeZoom.animateTo(1f, spring(0.78f, 260f * motionScale()))
            }
        } else if (homeZoom.value != 1f) {
            scope.launch { homeZoom.animateTo(1f) }
        }
    }

    fun updateLayout(f: (HomeLayout) -> HomeLayout) = graph.layout.update(f)

    fun removeFromHome(itemId: Long) {
        updateLayout { LayoutOps.normalizeFolders(LayoutOps.removeItem(it, itemId)) }
    }

    fun removeFromFolder(folderId: Long, app: AppKey) {
        // Moves the app out of the folder onto the current page (or the library if full).
        val s = graph.settings.value
        val page = pager?.currentPage ?: 0
        updateLayout { l ->
            val cell = l.pages.getOrNull(page)?.let { GridMath.findFree(it.items, 1, 1, s.gridColumns, s.gridRows) }
            if (cell != null) LayoutOps.drop(l, DragPayload.FromFolder(folderId, app), DropTarget.PageCell(page, cell), s.gridColumns, s.gridRows, s.dockMaxApps, ::folderNameFor)
            else LayoutOps.normalizeFolders(l.copy(pages = l.pages.map { p -> p.copy(items = p.items.map { if (it is FolderItem && it.id == folderId) it.copy(apps = it.apps - app) else it }) }))
        }
    }

    fun addToHome(app: AppKey) {
        val s = graph.settings.value
        if (app in graph.layout.value.placedApps()) { showToast("Bereits auf dem Home-Bildschirm"); return }
        updateLayout { LayoutOps.addApps(it, listOf(app), s.gridColumns, s.gridRows) }
        showToast("Zum Home-Bildschirm hinzugefügt")
    }

    /** Folder names follow the App Library category of the first app (like iOS). */
    fun folderNameFor(apps: List<AppKey>): String {
        val cats = apps.mapNotNull { k -> graph.apps.find(k)?.systemCategory }
        val c = cats.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: AppCategory.OTHER
        return when (c) {
            AppCategory.OTHER -> "Ordner"
            else -> c.title.substringBefore(" &")
        }
    }

    // ---------------------------------------------------------------- drag & drop

    fun beginPendingDrag(p: PendingDrag) {
        drag.pending = p
    }

    fun startDrag(pointer: Offset) {
        val p = drag.pending ?: return
        if (isLandscape) { drag.pending = null; showToast("Anordnen ist im Hochformat möglich"); return }
        drag.pending = null
        menu = null
        // Dragging an app out of the library or search places it on the home screen.
        if (p.payload is DragPayload.FromLibrary) {
            if (isOpen(Overlay.LIBRARY)) close(Overlay.LIBRARY)
            if (isOpen(Overlay.SEARCH)) close(Overlay.SEARCH)
        }
        drag.grab = pointer - p.bounds.topLeft
        drag.pointer = pointer
        drag.page = pager?.currentPage ?: 0
        drag.target = null
        drag.folderHover = null
        drag.active = p
        editMode = true
        haptic(Haptic.LONG_PRESS)
    }

    private var dwellJob: Job? = null
    private var dwellKey: Any? = null
    private var edgeJob: Job? = null

    fun moveDrag(pointer: Offset) {
        val p = drag.active ?: return
        drag.pointer = pointer
        val g = geometry
        // Leaving an open folder closes it and continues the drag on the home screen.
        openFolder?.let { if (g.folderPanel?.contains(pointer) == false) { openFolder = null; g.folderPanel = null } }
        if (openFolder != null) { drag.target = null; return }

        val isAppLike = p.visual is DragVisual.App || p.visual is DragVisual.Folder
        // ---- dock
        if (g.dockBounds.contains(pointer) && isAppLike) {
            edgeJob?.cancel(); edgeJob = null
            val over = g.dockSlots.firstOrNull { (id, r) -> id != p.sourceItemId && r.deflate(r.width * 0.22f).contains(pointer) }
            if (over != null && p.visual is DragVisual.App) {
                dwell(over.first) { drag.folderHover = over.first; drag.target = DropTarget.OntoItem(over.first); haptic(Haptic.TICK) }
                if (drag.folderHover != over.first) drag.target = DropTarget.Dock(g.dockSlots.indexOfFirst { it.first == over.first })
                return
            }
            cancelDwell()
            val idx = g.dockSlots.filter { it.first != p.sourceItemId }.count { it.second.center.x < pointer.x }
            drag.target = DropTarget.Dock(idx)
            return
        }
        // ---- edge paging
        val edge = 22f * (view?.resources?.displayMetrics?.density ?: 3f)
        val pagerState = pager
        if (pagerState != null && (pointer.x < edge || pointer.x > g.screen.width - edge)) {
            if (edgeJob == null) {
                edgeJob = scope.launch {
                    delay(550)
                    val dir = if (pointer.x < edge) -1 else 1
                    val target = (drag.page + dir)
                    val pages = graph.layout.value.pages.size
                    if (target in 0 until pages) {
                        drag.page = target
                        haptic(Haptic.TICK)
                        pagerState.animateScrollToPage(target)
                    } else if (target == pages && pages < 15) {
                        updateLayout { LayoutOps.addPage(it) }
                        drag.page = target
                        delay(32)
                        pagerState.animateScrollToPage(target)
                    }
                    edgeJob = null
                }
            }
        } else {
            edgeJob?.cancel(); edgeJob = null
        }
        // ---- grid
        if (g.cellSize.width <= 0f) return
        val (sw, sh) = p.span
        val topLeft = pointer - drag.grab
        val cx = ((topLeft.x - g.gridOrigin.x) / g.cellSize.width + 0.5f).toInt().coerceIn(0, g.cols - sw)
        val cy = ((topLeft.y - g.gridOrigin.y) / g.cellSize.height + 0.5f).toInt().coerceIn(0, g.rows - sh)
        val page = graph.layout.value.pages.getOrNull(drag.page) ?: return
        // pointer-cell (for merging into folders) uses the finger position itself
        val px = ((pointer.x - g.gridOrigin.x) / g.cellSize.width).toInt()
        val py = ((pointer.y - g.gridOrigin.y) / g.cellSize.height).toInt()
        val under = GridMath.itemAt(page.items, px, py)?.takeIf { it.id != p.sourceItemId }
        if (under != null && isAppLike && (under is AppItem || under is FolderItem) &&
            !(p.visual is DragVisual.Folder && under is AppItem) && !(p.visual is DragVisual.Folder && under is FolderItem)
        ) {
            val r = g.cellRect(under.cell)
            val core = r.deflate(r.width * 0.2f)
            if (core.contains(pointer)) {
                dwell(under.id) { drag.folderHover = under.id; drag.target = DropTarget.OntoItem(under.id); haptic(Haptic.TICK) }
                if (drag.folderHover == under.id) return
                drag.target = DropTarget.PageCell(drag.page, Cell(cx, cy, sw, sh))
                return
            }
        }
        cancelDwell()
        drag.target = DropTarget.PageCell(drag.page, Cell(cx, cy, sw, sh))
    }

    private fun dwell(key: Any, onDwell: () -> Unit) {
        if (dwellKey == key) return
        dwellJob?.cancel()
        drag.folderHover = null
        dwellKey = key
        dwellJob = scope.launch { delay(420); onDwell() }
    }

    private fun cancelDwell() {
        dwellJob?.cancel(); dwellJob = null; dwellKey = null; drag.folderHover = null
    }

    fun endDrag() {
        val p = drag.active ?: return
        cancelDwell()
        edgeJob?.cancel(); edgeJob = null
        val target = drag.target
        val s = graph.settings.value
        val before = graph.layout.value
        val after = if (target != null) {
            LayoutOps.drop(before, p.payload, target, s.gridColumns, s.gridRows, s.dockMaxApps, ::folderNameFor)
        } else before
        val success = after !== before
        if (success) graph.layout.set(after)
        // Fly the visual into its final slot.
        val dest: CRect? = when {
            target is DropTarget.PageCell && success -> {
                val placedId = (p.payload as? DragPayload.Existing)?.itemId
                val item = placedId?.let { LayoutOps.findItem(after, it) }
                geometry.cellRect(item?.cell ?: target.cell)
            }
            target is DropTarget.OntoItem && success -> geometry.dockSlots.firstOrNull { it.first == target.itemId }?.second
                ?: LayoutOps.findItem(after, target.itemId)?.let { geometry.cellRect(it.cell) }
            target is DropTarget.Dock && success -> null
            else -> p.bounds
        }
        val inDock = dest != null && geometry.dockBounds.contains(dest.center)
        scope.launch {
            if (dest != null) {
                drag.settling = true
                drag.settle.snapTo(drag.pointer - drag.grab)
                val visualTopLeft = when {
                    p.visual is DragVisual.Widget -> dest.topLeft + Offset(geometry.widgetInset, geometry.widgetInset)
                    inDock || dest == p.bounds -> Offset(dest.center.x - p.bounds.width / 2f, dest.center.y - p.bounds.height / 2f)
                    else -> Offset(dest.center.x - p.bounds.width / 2f, dest.top + geometry.iconTopInCell)
                }
                drag.settle.animateTo(visualTopLeft, spring(0.85f, Spring.StiffnessMediumLow * motionScale()))
            }
            drag.settling = false
            drag.active = null
            drag.target = null
        }
        if (success) haptic(Haptic.CONFIRM)
        if (!success && target != null) showToast("Kein Platz an dieser Stelle")
    }

    fun cancelDrag() {
        drag.pending = null
        if (drag.active != null) { cancelDwell(); drag.active = null; drag.target = null }
    }
}
