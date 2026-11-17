package de.lumen.launcher.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.AppItem
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.DropTarget
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.GridMath
import de.lumen.launcher.data.layout.HomeItem
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.layout.NativeWidgetItem
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.FolderOpen
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.PendingDrag
import de.lumen.launcher.ui.common.animatePlacement
import de.lumen.launcher.ui.glass.liquidPress
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import de.lumen.launcher.ui.widgets.WidgetHost

object HomeDimens {
    val dockCorner = 34.dp
    val dockSideMargin = 12.dp
    val dockInnerPad = 14.dp
    val widgetInset = 9.dp
    val widgetCorner = 24.dp
    val indicatorHeight = 34.dp
}

@Composable
fun HomeScreen(c: LauncherController, layout: HomeLayout, modifier: Modifier = Modifier) {
    val s = Lumen.settings
    val pageCount = layout.pages.size.coerceAtLeast(1)
    val pager = rememberPagerState { pageCount }
    SideEffect { c.pager = pager }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val landscape = c.isLandscape
    val iconSizeSetting = s.iconSizeDp.dp

    // Keep the pager in range when pages are removed.
    LaunchedEffect(pageCount) {
        if (pager.currentPage >= pageCount) pager.scrollToPage(pageCount - 1)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        SideEffect { c.geometry.screen = Size(screenW, screenH) }
        // In landscape the cells are short: scale icons to the available cell height.
        val landscapeCellH = (maxHeight - statusTop - navBottom - 160.dp) / s.gridColumns
        val iconSize = if (landscape) minOf(iconSizeSetting, landscapeCellH * 0.62f).coerceAtLeast(28.dp) else iconSizeSetting
        val dockIcon = if (landscape) minOf(iconSizeSetting * s.dockScale, 46.dp) else iconSizeSetting * s.dockScale
        val dockHeight = dockIcon + (if (landscape) 8.dp else HomeDimens.dockInnerPad) * 2

        Column(Modifier.fillMaxSize()) {
            val editTop by animateDpAsState(if (c.editMode && c.drag.active == null) 46.dp else 0.dp, Lumen.motion.smooth(), label = "editTop")
            Spacer(Modifier.height(statusTop + (if (landscape) 4.dp else 12.dp) + editTop))
            val sidePad = (if (landscape) 40.dp else 14.dp) * s.iconSpacing.coerceIn(0.6f, 1.4f)
            val cols = if (landscape) s.gridRows else s.gridColumns
            val rows = if (landscape) s.gridColumns else s.gridRows

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        val pad = with(density) { sidePad.toPx() }
                        val g = c.geometry
                        g.gridBounds = b
                        g.gridOrigin = Offset(b.left + pad, b.top)
                        g.cols = cols
                        g.rows = rows
                        g.cellSize = Size((b.width - pad * 2) / cols, b.height / rows)
                        val labelH = if (s.showLabels) with(density) { (5.dp + LumenType.iconLabel.lineHeight.toDp() * s.labelScale).toPx() } else 0f
                        g.iconTopInCell = ((g.cellSize.height - with(density) { iconSize.toPx() } - labelH) / 2f).coerceAtLeast(0f)
                        g.widgetInset = with(density) { HomeDimens.widgetInset.toPx() }
                    },
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = c.drag.active == null,
                    flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = Lumen.motion.spring(0.9f, 320f)),
                    key = { layout.pages.getOrNull(it)?.id ?: it.toLong() },
                ) { pageIndex ->
                    val page = layout.pages.getOrNull(pageIndex)
                    val items = remember(page, landscape, cols, rows) {
                        val list = page?.items.orEmpty()
                        if (landscape) GridMath.arrangeForGrid(list, cols, rows) else list
                    }
                    PageGrid(
                        c, items, cols, rows, pageIndex, iconSize,
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = sidePad)
                            .pageTransition(pager, pageIndex),
                    )
                }
                DropGhost(c, iconSize)
            }

            Box(Modifier.fillMaxWidth().height(HomeDimens.indicatorHeight + if (landscape) 4.dp else 14.dp), contentAlignment = Alignment.Center) {
                PageIndicator(c, pager, pageCount)
            }
            Dock(c, layout.dock, dockIcon, Modifier.height(dockHeight).then(if (landscape) Modifier.padding(horizontal = 120.dp) else Modifier))
            Spacer(Modifier.height(navBottom + if (landscape) 4.dp else 10.dp))
        }
        EditModeBar(c, statusTop)
    }
}

/** Subtle depth effect while swiping between pages: neighbours shrink and fade slightly. */
private fun Modifier.pageTransition(pager: PagerState, page: Int) = graphicsLayer {
    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
    val abs = kotlin.math.abs(offset)
    val scale = 1f - 0.06f * abs
    scaleX = scale
    scaleY = scale
    alpha = 1f - 0.35f * abs
}

@Composable
private fun PageGrid(
    c: LauncherController,
    items: List<HomeItem>,
    cols: Int,
    rows: Int,
    pageIndex: Int,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier.pointerInput(c.editMode) {
            detectTapGestures(
                onDoubleTap = { if (!c.editMode) c.run(c.graph.settings.value.gestures.doubleTap) },
                onLongPress = {
                    if (!c.editMode) {
                        c.haptic(Haptic.LONG_PRESS)
                        c.editMode = true
                    }
                },
                onTap = { if (c.editMode) c.editMode = false },
            )
        },
        content = {
            items.forEach { item ->
                key(item.id) {
                    HomeItemView(c, item, pageIndex, iconSize, Modifier.animatePlacement())
                }
            }
        },
    ) { measurables, constraints ->
        val cw = constraints.maxWidth / cols.toFloat()
        val ch = constraints.maxHeight / rows.toFloat()
        val placeables = measurables.mapIndexed { i, m ->
            val cell = items[i].cell
            m.measure(Constraints.fixed((cell.w * cw).toInt().coerceAtLeast(1), (cell.h * ch).toInt().coerceAtLeast(1)))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val cell = items[i].cell
                p.place((cell.x * cw).toInt(), (cell.y * ch).toInt())
            }
        }
    }
}

@Composable
private fun HomeItemView(c: LauncherController, item: HomeItem, pageIndex: Int, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val apps = LocalApps.current
    val dragging = c.drag.active
    val hidden = dragging?.sourceItemId == item.id
    when (item) {
        is AppItem -> {
            val app = apps[item.app] ?: return
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HomeTile(
                    label = app.label,
                    iconSize = iconSize,
                    jiggle = c.editMode,
                    seed = item.id,
                    hidden = hidden,
                    highlighted = c.drag.folderHover == item.id,
                    work = app.isWorkProfile,
                    onTap = { b -> c.launch(app, b) },
                    onLongPress = { b, down ->
                        c.haptic(Haptic.LONG_PRESS)
                        c.menu = MenuState(MenuTarget.App(app, item), b)
                        c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.App(app.key), b, down, item.id))
                    },
                    onEditPress = if (c.editMode) { b, down ->
                        c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.App(app.key), b, down, item.id))
                    } else null,
                    onRemove = { c.removeFromHome(item.id) },
                ) { m -> IconImageView(rememberAppIcon(app), iconSize, m) }
            }
        }
        is FolderItem -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HomeTile(
                label = item.name,
                iconSize = iconSize,
                jiggle = c.editMode,
                seed = item.id,
                hidden = hidden,
                highlighted = c.drag.folderHover == item.id,
                onTap = { b -> c.haptic(Haptic.TICK); c.openFolder = FolderOpen(item.id, b) },
                onLongPress = { b, down ->
                    c.haptic(Haptic.LONG_PRESS)
                    c.menu = MenuState(MenuTarget.Folder(item), b)
                    c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Folder(item), b, down, item.id))
                },
                onEditPress = if (c.editMode) { b, down ->
                    c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Folder(item), b, down, item.id))
                } else null,
                onRemove = null,
            ) { m -> FolderIcon(item, iconSize, m) }
        }
        is WidgetItem, is NativeWidgetItem -> Box(modifier.fillMaxSize().padding(HomeDimens.widgetInset)) {
            WidgetHost(c, item, hidden = hidden)
        }
    }
}

/** Glass outline showing where the dragged item will land. */
@Composable
private fun DropGhost(c: LauncherController, iconSize: androidx.compose.ui.unit.Dp) {
    val target = c.drag.target as? DropTarget.PageCell ?: return
    val active = c.drag.active ?: return
    if (c.drag.folderHover != null || c.drag.settling) return
    val g = c.geometry
    val density = LocalDensity.current
    val r = g.cellRect(target.cell)
    val local = Offset(r.left - g.gridBounds.left, r.top - g.gridBounds.top)
    val isWidget = active.visual is DragVisual.Widget
    val w = with(density) { (if (isWidget) r.width - g.widgetInset * 2 else iconSize.toPx()).toDp() }
    val h = with(density) { (if (isWidget) r.height - g.widgetInset * 2 else iconSize.toPx()).toDp() }
    val x = with(density) { (local.x + if (isWidget) g.widgetInset else (r.width - iconSize.toPx()) / 2f).toDp() }
    val y = with(density) { (local.y + if (isWidget) g.widgetInset else g.iconTopInCell).toDp() }
    Box(
        Modifier
            .padding(start = x, top = y)
            .size(w, h)
            .glass(
                if (isWidget) SmoothRoundedShape(HomeDimens.widgetCorner) else de.lumen.launcher.core.SquircleShape,
                GlassStyle(tint = Color.White.copy(alpha = 0.12f), elevation = 0f),
            )
    )
}

@Composable
private fun Dock(c: LauncherController, dock: List<HomeItem>, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val s = Lumen.settings
    val colors = Lumen.colors
    val drag = c.drag.active
    val dockTarget = (c.drag.target as? DropTarget.Dock)?.index
    val visible = dock.filter { it.id != drag?.sourceItemId || c.drag.settling }
    val showGap = drag != null && dockTarget != null && !c.drag.settling && visible.size < s.dockMaxApps &&
        (drag.visual is DragVisual.App || drag.visual is DragVisual.Folder)
    SideEffect { c.geometry.dockIds = dock.map { it.id } }
    val tint = (if (colors.isDark) Color(0xFF1C1C1E) else Color.White).copy(alpha = 0.08f + 0.5f * s.dockOpacity)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.dockSideMargin)
            .glass(SmoothRoundedShape(HomeDimens.dockCorner), GlassStyle(tint = tint, refraction = true, elevation = 0.9f))
            .onGloballyPositioned { c.geometry.dockBounds = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val entries: List<HomeItem?> = if (showGap) visible.toMutableList<HomeItem?>().apply { add(dockTarget!!.coerceIn(0, size), null) } else visible
            entries.forEach { item ->
                if (item == null) {
                    key("gap") { Spacer(Modifier.size(iconSize).animatePlacement()) }
                } else key(item.id) {
                    DockSlot(c, item, iconSize, Modifier.animatePlacement().popOnChange(item.id))
                }
            }
            if (entries.isEmpty()) {
                BasicText(
                    "Apps hierher ziehen",
                    style = LumenType.footnote.copy(color = Color.White.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun DockSlot(c: LauncherController, item: HomeItem, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val apps = LocalApps.current
    val hidden = c.drag.active?.sourceItemId == item.id
    Box(modifier.onGloballyPositioned { c.geometry.dockSlotMap[item.id] = it.boundsInRoot() }) {
        when (item) {
            is AppItem -> {
                val app = apps[item.app]
                if (app == null) {
                    Spacer(Modifier.size(iconSize))
                    return@Box
                }
                HomeTile(
                    label = null, iconSize = iconSize, jiggle = c.editMode, seed = item.id, hidden = hidden,
                    highlighted = c.drag.folderHover == item.id,
                    work = app.isWorkProfile,
                    onTap = { b -> c.launch(app, b) },
                    onLongPress = { b, down ->
                        c.haptic(Haptic.LONG_PRESS)
                        c.menu = MenuState(MenuTarget.App(app, item), b)
                        c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.App(app.key), b, down, item.id))
                    },
                    onEditPress = if (c.editMode) { b, down ->
                        c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.App(app.key), b, down, item.id))
                    } else null,
                    onRemove = { c.removeFromHome(item.id) },
                ) { m -> IconImageView(rememberAppIcon(app), iconSize, m) }
            }
            is FolderItem -> HomeTile(
                label = null, iconSize = iconSize, jiggle = c.editMode, seed = item.id, hidden = hidden,
                highlighted = c.drag.folderHover == item.id,
                onTap = { b -> c.openFolder = FolderOpen(item.id, b) },
                onLongPress = { b, down ->
                    c.haptic(Haptic.LONG_PRESS)
                    c.menu = MenuState(MenuTarget.Folder(item), b)
                    c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Folder(item), b, down, item.id))
                },
                onEditPress = if (c.editMode) { b, down ->
                    c.beginPendingDrag(PendingDrag(DragPayload.Existing(item.id), DragVisual.Folder(item), b, down, item.id))
                } else null,
            ) { m -> FolderIcon(item, iconSize, m) }
            else -> Unit
        }
    }
}

/**
 * Below the grid: a glass "Suchen" pill (iOS 26 style). While paging or editing it turns into
 * page dots so the user sees where they are.
 */
@Composable
private fun PageIndicator(c: LauncherController, pager: PagerState, count: Int) {
    val showDots = pager.isScrollInProgress || c.editMode || c.drag.active != null
    Crossfade(showDots, animationSpec = Lumen.motion.fade(180), label = "indicator") { dots ->
        if (dots) {
            Row(
                Modifier
                    .height(26.dp)
                    .glass(CircleShape, GlassStyle(elevation = 0.3f))
                    .liquidPress(c.editMode) { c.pageManagerOpen = true }
                    .padding(horizontal = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(count) { i ->
                    val active = i == pager.currentPage
                    val a by animateFloatAsState(if (active) 1f else 0.4f, label = "dot")
                    val w by animateDpAsState(if (active) 7.dp else 7.dp, label = "dotw")
                    Box(Modifier.size(w).clip(CircleShape).background(Color.White.copy(alpha = a)))
                }
            }
        } else {
            Row(
                Modifier
                    .height(HomeDimens.indicatorHeight)
                    .glass(CircleShape, GlassStyle(elevation = 0.4f))
                    .liquidPress { c.open(de.lumen.launcher.ui.Overlay.SEARCH) }
                    .padding(start = 12.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(17.dp))
                BasicText("Suchen", style = LumenType.subhead.copy(color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            }
        }
    }
}

/** Edit mode: "+" (widgets) and "Fertig" capsules at the top, like the iOS jiggle mode. */
@Composable
private fun EditModeBar(c: LauncherController, statusTop: androidx.compose.ui.unit.Dp) {
    AnimatedVisibility(
        visible = c.editMode && c.drag.active == null,
        enter = fadeIn(Lumen.motion.fade()) + scaleIn(Lumen.motion.bouncy(), initialScale = 0.8f),
        exit = fadeOut(Lumen.motion.fade()) + scaleOut(targetScale = 0.9f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = statusTop + 4.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .glass(CircleShape, GlassStyle(elevation = 0.6f))
                    .liquidPress { c.widgetPickerOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "Widget hinzufügen", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier
                    .height(36.dp)
                    .glass(RoundedCornerShape(50), GlassStyle(elevation = 0.6f))
                    .liquidPress { c.editMode = false }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Fertig", style = LumenType.headline.copy(color = Color.White, fontSize = LumenType.subhead.fontSize))
            }
        }
    }
}
