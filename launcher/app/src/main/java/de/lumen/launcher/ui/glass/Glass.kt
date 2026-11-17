package de.lumen.launcher.ui.glass

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.lumen.launcher.ui.theme.LocalLumenColors
import de.lumen.launcher.ui.theme.LocalSettings
import de.lumen.launcher.ui.wallpaper.LocalWallpaper
import de.lumen.launcher.ui.wallpaper.WallpaperState
import kotlin.math.roundToInt

/**
 * Visual parameters of a glass surface.
 *
 * @param tint colour laid over the blurred backdrop (null = theme default)
 * @param sampleWallpaper draw the blurred wallpaper below the surface (home-level glass). Glass on
 *   top of an already blurred overlay sets this to false.
 * @param elevation soft drop shadow strength (0 = none)
 * @param sheen strength of the top-light gradient and specular rim
 */
@Immutable
data class GlassStyle(
    val tint: Color? = null,
    val sampleWallpaper: Boolean = true,
    val elevation: Float = 1f,
    val sheen: Float = 1f,
    val refraction: Boolean = false,
)

/**
 * Liquid-glass surface: blurred & saturated backdrop, tint, top sheen, specular rim and shadow.
 *
 * Home-level glass samples a pre-blurred copy of the launcher wallpaper at the element's on-screen
 * position. This costs one bitmap draw per frame (no live blur) and works on every Android version.
 */
fun Modifier.glass(shape: Shape, style: GlassStyle = GlassStyle()): Modifier = this then GlassElement(shape, style)

private data class GlassElement(val shape: Shape, val style: GlassStyle) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(shape, style)
    override fun update(node: GlassNode) {
        node.shape = shape
        node.style = style
        node.pathSize = Size.Unspecified
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glass"
    }
}

private class GlassNode(var shape: Shape, var style: GlassStyle) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var posInRoot = Offset.Unspecified
    var pathSize = Size.Unspecified
    private val path = Path()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != posInRoot) {
            posInRoot = p
            if (style.sampleWallpaper) invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalLumenColors)
        val settings = currentValueOf(LocalSettings)
        val wp = currentValueOf(LocalWallpaper)
        if (pathSize != size) {
            path.reset()
            path.addOutline(shape.createOutline(size, layoutDirection, this))
            pathSize = size
        }
        if (style.elevation > 0f) drawSoftShadow(path, colors.shadow, style.elevation)

        clipPath(path) {
            val pos = if (posInRoot.isSpecified) posInRoot else Offset.Zero
            if (style.sampleWallpaper) {
                if (!settings.reduceTransparency && wp.blurred != null) {
                    if (style.refraction && settings.liquidRefraction && Build.VERSION.SDK_INT >= 33) {
                        LiquidRefraction.draw(this, wp, pos, path, size)
                    } else {
                        drawWallpaperRegion(wp, pos, size)
                    }
                } else {
                    drawRect(if (colors.isDark) Color(0xF02A2A2E) else Color(0xF0E9E9EE))
                }
            }
            val tint = style.tint ?: colors.glassTint
            drawRect(tint)
            if (style.sheen > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = (if (colors.isDark) 0.07f else 0.16f) * style.sheen),
                        0.5f to Color.Transparent,
                    )
                )
            }
        }
        if (style.sheen > 0f) drawRim(path, colors.rimHighlight, colors.rimShade, style.sheen)
        drawContent()
    }
}

/** Draws the part of the blurred wallpaper that lies under a rectangle at [pos] (root coordinates). */
fun DrawScope.drawWallpaperRegion(wp: WallpaperState, pos: Offset, size: Size, alpha: Float = 1f) {
    val bl = wp.blurred ?: return
    val s = wp.blurScale
    if (s <= 0f) return
    val sx = pos.x * s
    val sy = pos.y * s
    val l = sx.coerceIn(0f, bl.width.toFloat())
    val t = sy.coerceIn(0f, bl.height.toFloat())
    val r = (sx + size.width * s).coerceIn(0f, bl.width.toFloat())
    val b = (sy + size.height * s).coerceIn(0f, bl.height.toFloat())
    if (r - l < 0.5f || b - t < 0.5f) return
    val srcOff = IntOffset(l.toInt(), t.toInt())
    val srcSize = IntSize(((r - l).roundToInt()).coerceAtLeast(1), ((b - t).roundToInt()).coerceAtLeast(1))
    drawImage(
        image = bl,
        srcOffset = srcOff,
        srcSize = srcSize,
        dstOffset = IntOffset(((srcOff.x - sx) / s).roundToInt(), ((srcOff.y - sy) / s).roundToInt()),
        dstSize = IntSize((srcSize.width / s).roundToInt(), (srcSize.height / s).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Specular rim: bright at the top-left light source, fading along the edge. */
fun DrawScope.drawRim(path: Path, highlight: Color, shade: Color, strength: Float = 1f, width: Dp = 1.dp) {
    drawPath(
        path,
        brush = Brush.linearGradient(
            0f to highlight.copy(alpha = highlight.alpha * strength),
            0.35f to shade.copy(alpha = shade.alpha * strength),
            0.65f to shade.copy(alpha = shade.alpha * 0.6f * strength),
            1f to highlight.copy(alpha = highlight.alpha * 0.55f * strength),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width.toPx()),
    )
}

private val shadowPaint by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

/** Soft ambient shadow (hardware-accelerated BlurMaskFilter is available from Android 9). */
fun DrawScope.drawSoftShadow(path: Path, color: Color, strength: Float = 1f) {
    if (Build.VERSION.SDK_INT < 28) return
    val radius = 18.dp.toPx() * strength
    drawIntoCanvas { canvas ->
        shadowPaint.color = color.copy(alpha = color.alpha * 0.8f * strength.coerceAtMost(1f)).toArgb()
        shadowPaint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        val nc = canvas.nativeCanvas
        nc.save()
        nc.translate(0f, 6.dp.toPx() * strength)
        nc.drawPath(path.asAndroidPath(), shadowPaint)
        nc.restore()
    }
}

// ============================================================================ live backdrop blur

/**
 * Live backdrop: the scene (wallpaper + home screen) records itself into a [GraphicsLayer];
 * overlays draw that layer with a GPU blur ([BlurEffect], Android 12+). On older versions the
 * pre-blurred wallpaper is used instead, so every overlay still looks frosted.
 */
@Stable
class Backdrop {
    internal var layer: GraphicsLayer? = null
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop = remember { Backdrop() }

@Composable
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    val layer = rememberGraphicsLayer()
    backdrop.layer = layer
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * Draws the blurred scene behind this node. [progress] (0..1) scales blur radius and opacity so
 * overlays can "blur in" interactively while they are dragged open.
 */
@Composable
fun Modifier.backdropBlur(
    backdrop: Backdrop,
    radius: Dp = 40.dp,
    progress: () -> Float = { 1f },
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    val pos = remember { floatArrayOf(0f, 0f) }
    return this
        .onGloballyPositioned {
            val p = it.positionInRoot()
            pos[0] = p.x; pos[1] = p.y
        }
        .drawBehind {
            val a = progress().coerceIn(0f, 1f)
            if (a <= 0.001f) return@drawBehind
            val src = backdrop.layer
            if (Build.VERSION.SDK_INT >= 31 && src != null) {
                val r = radius.toPx() * a
                blurLayer.renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                blurLayer.record {
                    translate(backdrop.origin.x - pos[0], backdrop.origin.y - pos[1]) { drawLayer(src) }
                }
                drawLayer(blurLayer)
            } else {
                // Fallback: frosted wallpaper fades in.
                // (Reading the local inside draw is not possible; the caller provides the
                // wallpaper through backdropFallback.)
                BackdropFallback.current?.let { wp ->
                    drawWallpaperRegion(wp, Offset(pos[0], pos[1]), size, alpha = a)
                }
            }
        }
}

/** Wallpaper used by [backdropBlur] on Android < 12 (set once by the root composable). */
object BackdropFallback {
    @Volatile
    var current: WallpaperState? = null
}
