package de.lumen.launcher.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.apps.AppInfo
import de.lumen.launcher.data.apps.LibraryCategory
import de.lumen.launcher.data.apps.LibraryModel
import de.lumen.launcher.data.apps.SearchEngine
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.Overlay
import de.lumen.launcher.ui.PendingDrag
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassIconButton
import de.lumen.launcher.ui.glass.GlassSearchField
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.OverlayFrame
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.home.HomeTile
import de.lumen.launcher.ui.home.IconImageView
import de.lumen.launcher.ui.home.rememberAppIcon
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.launch

/** iOS style App Library: category tiles, live search, alphabetical list. */
@Composable
fun LibraryOverlay(c: LauncherController, backdrop: Backdrop) {
    val anim = c.anim(Overlay.LIBRARY)
    if (!anim.visible) return
    val apps by c.graph.apps.apps.collectAsState()
    val lib by c.graph.library.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var openCategory by remember { mutableStateOf<LibraryCategory?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val now = remember(anim.target) { System.currentTimeMillis() }
    val categories = remember(apps, lib, now) { LibraryModel.build(apps, lib, now) }
    val visible = remember(apps, lib) { LibraryModel.visibleApps(apps, lib) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(anim.target) {
        if (anim.target == 0f) {
            query = ""; openCategory = null; alphabetical = false
            focus.clearFocus(); keyboard?.hide()
        }
    }

    // Pulling down at the top of the list closes the library (follows the finger).
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    scope.launch { pull.snapTo(pull.value + available.y) }
                    c.dragOverlay(Overlay.LIBRARY, (1f - pull.value / 1400f).coerceIn(0f, 1f))
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.value > 0f && available.y < 0f) {
                    val use = maxOf(available.y, -pull.value)
                    scope.launch { pull.snapTo(pull.value + use) }
                    c.dragOverlay(Overlay.LIBRARY, (1f - (pull.value + use) / 1400f).coerceIn(0f, 1f))
                    return Offset(0f, use)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value > 0f) {
                    val closing = pull.value > 220f || available.y > 1800f
                    pull.snapTo(0f)
                    if (closing) c.close(Overlay.LIBRARY) else c.open(Overlay.LIBRARY)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    OverlayFrame(backdrop, { anim.progress.value }, blur = 48.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = anim.progress.value.coerceIn(0f, 1f)
                        translationY = (1f - p) * h * 0.18f
                        alpha = p
                        val s = 0.94f + 0.06f * p
                        scaleX = s; scaleY = s
                    }
                    .padding(top = statusTop + 10.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .pointerInput(Unit) {
                            var acc = 0f
                            detectVerticalDragGestures(
                                onDragStart = { acc = 0f },
                                onDragEnd = { if (acc > 160f) c.close(Overlay.LIBRARY) else c.open(Overlay.LIBRARY) },
                                onVerticalDrag = { ch, dy ->
                                    ch.consume()
                                    acc = (acc + dy).coerceAtLeast(0f)
                                    c.dragOverlay(Overlay.LIBRARY, 1f - acc / (h * 0.6f))
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassSearchField(query, { query = it }, "App-Bibliothek", Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    GlassIconButton(
                        if (alphabetical) Icons.Rounded.GridView else Icons.Rounded.SortByAlpha,
                        if (alphabetical) "Kategorien" else "Alphabetisch",
                    ) { alphabetical = !alphabetical }
                }
                Spacer(Modifier.height(14.dp))
                val mode = when {
                    query.isNotBlank() -> 2
                    alphabetical -> 1
                    else -> 0
                }
                val motion = Lumen.motion
                AnimatedContent(mode, transitionSpec = { fadeIn(motion.fade(160)) togetherWith fadeOut(motion.fade(120)) }, label = "libmode") { m ->
                    when (m) {
                        0 -> CategoryGrid(c, categories, Modifier.nestedScroll(nested), navBottom) { openCategory = it }
                        1 -> AlphabeticalList(c, LibraryModel.alphabetical(visible), Modifier.nestedScroll(nested), navBottom)
                        else -> AppList(c, SearchEngine.search(visible, query, lib.usage, now, 60), Modifier.nestedScroll(nested), navBottom, empty = "Keine Treffer für „$query“")
                    }
                }
            }
            openCategory?.let { cat -> CategoryDetail(c, cat) { openCategory = null } }
        }
    }
}

// ------------------------------------------------------------------ category grid

@Composable
private fun CategoryGrid(c: LauncherController, cats: List<LibraryCategory>, modifier: Modifier, navBottom: Dp, onExpand: (LibraryCategory) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navBottom + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(cats, key = { it.id }) { cat -> CategoryTile(c, cat, onExpand) }
    }
}

@Composable
private fun CategoryTile(c: LauncherController, cat: LibraryCategory, onExpand: (LibraryCategory) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .glass(SmoothRoundedShape(28.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTint, elevation = 0.5f))
                .padding(14.dp),
        ) {
            val gap = 12.dp
            val icon = (maxWidth - gap) / 2
            val big = if (cat.apps.size <= 4) cat.apps.take(4) else cat.apps.take(3)
            val rest = if (cat.apps.size > 4) cat.apps.drop(3) else emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until 2) {
                            val i = r * 2 + col
                            when {
                                i < big.size -> LibraryIcon(c, big[i], icon, label = false)
                                i == 3 && rest.isNotEmpty() -> MiniCluster(rest, icon) { c.haptic(Haptic.TICK); onExpand(cat) }
                                else -> Spacer(Modifier.size(icon))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            cat.title,
            style = LumenType.footnote.copy(color = Lumen.colors.textPrimary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniCluster(apps: List<AppInfo>, size: Dp, onClick: () -> Unit) {
    val gap = size * 0.08f
    val mini = (size - gap) / 2
    Column(
        Modifier.size(size).pointerInput(Unit) { detectTapGestures { onClick() } },
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (col in 0 until 2) {
                val a = apps.getOrNull(r * 2 + col)
                if (a != null) IconImageView(rememberAppIcon(a), mini, sampleWallpaper = false) else Spacer(Modifier.size(mini))
            }
        }
    }
}

/** App icon inside the library with tap, context menu and drag-to-home. */
@Composable
fun LibraryIcon(c: LauncherController, app: AppInfo, size: Dp, label: Boolean, onGlass: Boolean = false) {
    HomeTile(
        labelColor = if (onGlass) Lumen.colors.textPrimary else null,
        label = if (label) app.label else null,
        iconSize = size,
        work = app.isWorkProfile,
        hidden = c.drag.active?.payload == DragPayload.FromLibrary(app.key),
        onTap = { b -> c.launch(app, b) },
        onLongPress = { b, down ->
            c.haptic(Haptic.LONG_PRESS)
            c.menu = MenuState(MenuTarget.App(app, null, fromLibrary = true), b)
            c.beginPendingDrag(PendingDrag(DragPayload.FromLibrary(app.key), DragVisual.App(app.key), b, down, null))
        },
        onEditPress = null,
    ) { m -> IconImageView(rememberAppIcon(app), size, m, sampleWallpaper = false) }
}

// ------------------------------------------------------------------ lists

@Composable
private fun AppList(c: LauncherController, apps: List<AppInfo>, modifier: Modifier, navBottom: Dp, empty: String) {
    if (apps.isEmpty()) {
        Box(modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
            BasicText(empty, style = LumenType.body.copy(color = Lumen.colors.textSecondary))
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navBottom + 24.dp)) {
        items(apps, key = { it.key.value }) { app -> AppRow(c, app) }
    }
}

@Composable
fun AppRow(c: LauncherController, app: AppInfo) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryIcon(c, app, 44.dp, label = false)
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1f)
                .pointerInput(app.key) { detectTapGestures { c.launch(app, null) } },
        ) {
            BasicText(app.label, style = LumenType.body.copy(color = Lumen.colors.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.isWorkProfile) BasicText("Arbeitsprofil", style = LumenType.footnote.copy(color = Lumen.colors.textSecondary))
        }
    }
}

@Composable
private fun AlphabeticalList(c: LauncherController, groups: List<Pair<String, List<AppInfo>>>, modifier: Modifier, navBottom: Dp) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // index of each letter header in the flattened list
    val headerIndex = remember(groups) {
        var i = 0
        groups.associate { (letter, list) -> (letter to i).also { i += 1 + list.size } }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 36.dp, bottom = navBottom + 24.dp)) {
            groups.forEach { (letter, list) ->
                stickyHeader(key = "h-$letter") {
                    BasicText(
                        letter,
                        style = LumenType.headline.copy(color = Lumen.colors.textSecondary),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                items(list, key = { it.key.value }) { app -> AppRow(c, app) }
            }
        }
        // Letter index on the right edge: tap or slide to jump.
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .pointerInput(groups) {
                    fun jump(y: Float) {
                        val idx = (y / size.height * groups.size).toInt().coerceIn(0, groups.lastIndex)
                        val target = headerIndex[groups[idx].first] ?: 0
                        scope.launch { state.scrollToItem(target) }
                    }
                    detectVerticalDragGestures(onDragStart = { jump(it.y) }) { ch, _ -> ch.consume(); jump(ch.position.y) }
                }
                .pointerInput(groups) {
                    detectTapGestures { pos ->
                        val idx = (pos.y / size.height * groups.size).toInt().coerceIn(0, groups.lastIndex)
                        scope.launch { state.scrollToItem(headerIndex[groups[idx].first] ?: 0) }
                        c.haptic(Haptic.TICK)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            groups.forEach { (letter, _) ->
                BasicText(letter, style = LumenType.caption2.copy(color = Lumen.colors.accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ expanded category

@Composable
private fun CategoryDetail(c: LauncherController, cat: LibraryCategory, onClose: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val motion = Lumen.motion
    LaunchedEffect(cat.id) { progress.animateTo(1f, motion.spring(0.8f, 320f)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f * progress.value))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    val p = progress.value
                    scaleX = 0.7f + 0.3f * p; scaleY = 0.7f + 0.3f * p; alpha = p.coerceIn(0f, 1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(cat.title, style = LumenType.title1.copy(color = Color.White))
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .glass(SmoothRoundedShape(36.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTint, elevation = 1f))
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                val size = Lumen.settings.iconSizeDp.dp
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 22.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    cat.apps.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { a -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LibraryIcon(c, a, size, label = true) } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
