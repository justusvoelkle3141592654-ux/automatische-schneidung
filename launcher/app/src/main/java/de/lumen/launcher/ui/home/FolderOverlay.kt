package de.lumen.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import de.lumen.launcher.core.SmoothRoundedShape
import de.lumen.launcher.data.layout.DragPayload
import de.lumen.launcher.data.layout.FolderItem
import de.lumen.launcher.data.layout.LayoutOps
import de.lumen.launcher.ui.DragVisual
import de.lumen.launcher.ui.FolderOpen
import de.lumen.launcher.ui.Haptic
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.MenuState
import de.lumen.launcher.ui.MenuTarget
import de.lumen.launcher.ui.PendingDrag
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassStyle
import de.lumen.launcher.ui.glass.backdropBlur
import de.lumen.launcher.ui.glass.glass
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType

/** Rename request: when set, the folder opens with its title in edit mode. */
var pendingFolderRename by mutableStateOf<Long?>(null)

@Composable
fun FolderOverlay(c: LauncherController, backdrop: Backdrop) {
    val open = c.openFolder
    var shown by remember { mutableStateOf<FolderOpen?>(null) }
    val progress = remember { Animatable(0f) }
    val motion = Lumen.motion
    LaunchedEffect(open) {
        if (open != null) {
            shown = open
            progress.animateTo(1f, motion.spring(0.78f, 300f))
        } else if (shown != null) {
            progress.animateTo(0f, motion.spring(1f, 420f))
            shown = null
            c.geometry.folderPanel = null
        }
    }
    val current = shown ?: return
    val layout by c.graph.layout.state.collectAsState()
    val folder = LayoutOps.findItem(layout, current.folderId) as? FolderItem
    if (folder == null) {
        LaunchedEffect(Unit) { c.openFolder = null }
        return
    }
    val draggingOut = c.drag.active != null && open == null

    Box(
        Modifier
            .fillMaxSize()
            .backdropBlur(backdrop, 34.dp) { progress.value }
            .background(Color.Black.copy(alpha = 0.18f * progress.value))
            .pointerInput(Unit) { detectTapGestures { c.openFolder = null } },
    ) {
        if (draggingOut) return@Box
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val panelW = minOf(maxWidth * 0.84f, 340.dp)
            val iconSize = Lumen.settings.iconSizeDp.dp
            val rowsPerPage = 3
            val cellH = iconSize + 34.dp
            val panelH = cellH * rowsPerPage + 40.dp
            val screenCenter = with(density) { Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f) }
            val origin = current.origin

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FolderTitle(c, folder, Modifier.graphicsLayer { alpha = progress.value })
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .size(panelW, panelH)
                        .graphicsLayer {
                            val p = progress.value
                            val sx = (origin.width / panelW.toPx()).coerceIn(0.05f, 1f)
                            val sy = (origin.height / panelH.toPx()).coerceIn(0.05f, 1f)
                            scaleX = lerp(sx, 1f, p)
                            scaleY = lerp(sy, 1f, p)
                            translationX = lerp(origin.center.x - screenCenter.x, 0f, p)
                            translationY = lerp(origin.center.y - screenCenter.y - 20.dp.toPx(), 0f, p)
                            transformOrigin = TransformOrigin.Center
                        }
                        .glass(SmoothRoundedShape(38.dp), GlassStyle(sampleWallpaper = false, tint = Lumen.colors.glassTint.copy(alpha = 0.32f), elevation = 1f))
                        .onGloballyPositioned { c.geometry.folderPanel = it.boundsInRoot() }
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    val pages = folder.apps.chunked(9).ifEmpty { listOf(emptyList()) }
                    val pager = rememberPagerState { pages.size }
                    HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { alpha = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) }) { pi ->
                        FolderPage(c, folder, pages[pi], iconSize, cellH)
                    }
                    if (pages.size > 1) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            repeat(pages.size) { i ->
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPage(c: LauncherController, folder: FolderItem, apps: List<de.lumen.launcher.data.apps.AppKey>, iconSize: androidx.compose.ui.unit.Dp, cellH: androidx.compose.ui.unit.Dp) {
    val all = LocalApps.current
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
        apps.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(cellH), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    val app = all[key]
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (app != null) {
                            HomeTile(
                                label = app.label,
                                iconSize = iconSize,
                                jiggle = c.editMode,
                                seed = key.hashCode().toLong(),
                                hidden = c.drag.active?.payload == DragPayload.FromFolder(folder.id, key),
                                work = app.isWorkProfile,
                                onTap = { b -> c.launch(app, b) },
                                onLongPress = { b, down ->
                                    c.haptic(Haptic.LONG_PRESS)
                                    c.menu = MenuState(MenuTarget.App(app, null, folderId = folder.id), b)
                                    c.beginPendingDrag(PendingDrag(DragPayload.FromFolder(folder.id, key), DragVisual.App(key), b, down, null))
                                },
                                onEditPress = if (c.editMode) { b, down ->
                                    c.beginPendingDrag(PendingDrag(DragPayload.FromFolder(folder.id, key), DragVisual.App(key), b, down, null))
                                } else null,
                                onRemove = { c.removeFromFolder(folder.id, key) },
                            ) { m -> IconImageView(rememberAppIcon(app), iconSize, m, sampleWallpaper = false) }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FolderTitle(c: LauncherController, folder: FolderItem, modifier: Modifier) {
    var editing by remember(folder.id) { mutableStateOf(pendingFolderRename == folder.id) }
    var text by remember(folder.id) { mutableStateOf(folder.name) }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { pendingFolderRename = null }
    val style = LumenType.title1.copy(color = Color.White, textAlign = TextAlign.Center)
    if (editing) {
        val requester = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Lumen.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                c.updateLayout { LayoutOps.renameFolder(it, folder.id, text.trim()) }
                editing = false
                focus.clearFocus()
            }),
            modifier = modifier
                .width(300.dp)
                .glass(SmoothRoundedShape(14.dp), GlassStyle(sampleWallpaper = false, elevation = 0f, tint = Color.White.copy(alpha = 0.12f)))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .focusRequester(requester),
        )
    } else {
        androidx.compose.foundation.text.BasicText(
            folder.name,
            style = style,
            modifier = modifier.pointerInput(folder.id) { detectTapGestures { editing = true } },
        )
    }
}
