package de.lumen.launcher.ui.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import de.lumen.launcher.data.settings.LauncherSettings
import de.lumen.launcher.data.settings.WallpaperMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

@Immutable
data class WallpaperBlob(val x: Float, val y: Float, val radius: Float, val color: Color, val alpha: Float = 0.9f)

@Immutable
data class WallpaperPreset(
    val name: String,
    val top: Color,
    val bottom: Color,
    val blobs: List<WallpaperBlob>,
    val isLight: Boolean = false,
)

/** Original abstract "light field" wallpapers, rendered procedurally at the exact screen size. */
val WallpaperPresets = listOf(
    WallpaperPreset(
        "Aurora", Color(0xFF0B1033), Color(0xFF1A0E3A),
        listOf(
            WallpaperBlob(0.18f, 0.22f, 0.62f, Color(0xFF3D6BFF)),
            WallpaperBlob(0.88f, 0.34f, 0.55f, Color(0xFF9B5CFF)),
            WallpaperBlob(0.62f, 0.86f, 0.62f, Color(0xFFFF5FA2), 0.85f),
            WallpaperBlob(0.08f, 0.78f, 0.45f, Color(0xFF2DD4FF), 0.7f),
        ),
    ),
    WallpaperPreset(
        "Lagune", Color(0xFF021B2B), Color(0xFF063A4A),
        listOf(
            WallpaperBlob(0.2f, 0.18f, 0.6f, Color(0xFF19C6B8)),
            WallpaperBlob(0.9f, 0.45f, 0.55f, Color(0xFF3BA8FF)),
            WallpaperBlob(0.35f, 0.88f, 0.6f, Color(0xFF7CF2C9), 0.75f),
            WallpaperBlob(0.75f, 0.05f, 0.4f, Color(0xFF1B4BFF), 0.8f),
        ),
    ),
    WallpaperPreset(
        "Glut", Color(0xFF2A0A12), Color(0xFF3B0F05),
        listOf(
            WallpaperBlob(0.15f, 0.3f, 0.6f, Color(0xFFFF6A3D)),
            WallpaperBlob(0.85f, 0.2f, 0.5f, Color(0xFFFF2D6F)),
            WallpaperBlob(0.6f, 0.75f, 0.6f, Color(0xFFFFB84D), 0.8f),
            WallpaperBlob(0.1f, 0.95f, 0.45f, Color(0xFFB5179E), 0.85f),
        ),
    ),
    WallpaperPreset(
        "Perlmutt", Color(0xFFF4F1FA), Color(0xFFE6ECF8),
        listOf(
            WallpaperBlob(0.15f, 0.2f, 0.6f, Color(0xFFB8C8FF), 0.8f),
            WallpaperBlob(0.9f, 0.4f, 0.55f, Color(0xFFFFC9E3), 0.8f),
            WallpaperBlob(0.3f, 0.85f, 0.6f, Color(0xFFC8F1FF), 0.8f),
            WallpaperBlob(0.8f, 0.92f, 0.45f, Color(0xFFE5D4FF), 0.8f),
        ),
        isLight = true,
    ),
    WallpaperPreset(
        "Wald", Color(0xFF05150F), Color(0xFF0B2418),
        listOf(
            WallpaperBlob(0.2f, 0.25f, 0.6f, Color(0xFF2FBF71), 0.85f),
            WallpaperBlob(0.85f, 0.5f, 0.5f, Color(0xFF14B8A6), 0.8f),
            WallpaperBlob(0.45f, 0.9f, 0.55f, Color(0xFF8FCB3F), 0.6f),
            WallpaperBlob(0.9f, 0.08f, 0.4f, Color(0xFFF2C94C), 0.45f),
        ),
    ),
    WallpaperPreset(
        "Graphit", Color(0xFF0A0A0C), Color(0xFF16161A),
        listOf(
            WallpaperBlob(0.2f, 0.2f, 0.6f, Color(0xFF3A3A46), 0.9f),
            WallpaperBlob(0.85f, 0.55f, 0.55f, Color(0xFF2B3A55), 0.9f),
            WallpaperBlob(0.4f, 0.9f, 0.6f, Color(0xFF4B4B5E), 0.8f),
        ),
    ),
)

/**
 * Wallpaper drawn by the launcher. [blurred] is a small, heavily blurred and saturated copy that
 * glass surfaces sample from (cropped to the same screen rectangle as [image]).
 * For [WallpaperMode.SYSTEM] both are null and the system wallpaper shows through the window.
 */
@Immutable
class WallpaperState(
    val image: ImageBitmap?,
    val blurred: ImageBitmap?,
    val screen: IntSize,
    val isLight: Boolean,
    val key: Any,
) {
    /** Scale from screen pixels to [blurred] pixels. */
    val blurScale: Float get() = if (blurred == null || screen.width == 0) 0f else blurred.width / screen.width.toFloat()

    companion object {
        val Empty = WallpaperState(null, null, IntSize.Zero, false, Unit)
    }
}

val LocalWallpaper = staticCompositionLocalOf { WallpaperState.Empty }

object WallpaperRenderer {

    fun renderPreset(p: WallpaperPreset, w: Int, h: Int, dim: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p.top.toArgb(), p.bottom.toArgb(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val diag = hypot(w.toFloat(), h.toFloat())
        for (b in p.blobs) {
            val r = b.radius * diag
            val col = b.color.copy(alpha = b.alpha)
            paint.shader = RadialGradient(
                b.x * w, b.y * h, r,
                intArrayOf(col.toArgb(), col.copy(alpha = b.alpha * 0.45f).toArgb(), android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        paint.shader = null
        addGrain(c, w, h, if (p.isLight) 7 else 10)
        if (dim > 0f) c.drawColor(Color.Black.copy(alpha = dim.coerceIn(0f, 0.8f)).toArgb())
        return bmp
    }

    /** Fine film grain: hides gradient banding and gives the surface a premium, printed feel. */
    private fun addGrain(c: Canvas, w: Int, h: Int, alpha: Int) {
        val n = 128
        val noise = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = Random(42)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            android.graphics.Color.argb(alpha, v, v, v)
        }
        noise.setPixels(px, 0, n, 0, 0, n, n)
        val paint = Paint()
        paint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        noise.recycle()
    }

    /** Decodes a user image with sub-sampling and center-crops it to the screen size. */
    fun loadCustom(file: File, w: Int, h: Int, dim: Float): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val sw = w / scale
        val sh = h / scale
        val left = ((src.width - sw) / 2f).toInt()
        val top = ((src.height - sh) / 2f).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, Rect(left, top, (left + sw).toInt(), (top + sh).toInt()), RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        if (dim > 0f) c.drawColor(Color.Black.copy(alpha = dim.coerceIn(0f, 0.8f)).toArgb())
        return out
    }

    /**
     * Produces the glass sampling texture: 1/8 resolution, saturation boost (glass "vibrancy"),
     * then a 3-pass box blur (≈ gaussian). Drawn upscaled with bilinear filtering it is perfectly smooth.
     */
    fun blurForGlass(src: Bitmap): Bitmap {
        val factor = 8
        val w = (src.width / factor).coerceAtLeast(8)
        val h = (src.height / factor).coerceAtLeast(8)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.55f) })
        Canvas(small).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val radius = (w / 18).coerceAtLeast(2)
        repeat(3) { boxBlur(px, w, h, radius) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    /** Separable box blur on ARGB pixels (horizontal + vertical), edge-clamped. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            fun idx(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + k else k * w + line
            }
            for (i in -r..r) {
                val c = src[idx(i)]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[idx(i - r)]
                val inn = src[idx(i + r + 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}

/** Loads/renders the wallpaper for the current settings and screen size (off the main thread). */
@Composable
fun rememberWallpaperState(settings: LauncherSettings, screen: IntSize, darkTheme: Boolean): WallpaperState {
    val preset = WallpaperPresets.getOrElse(settings.wallpaperPreset) { WallpaperPresets[0] }
    // In dark mode light wallpapers are dimmed slightly, like the system does.
    val autoDim = if (darkTheme && (settings.wallpaperMode == WallpaperMode.CUSTOM || preset.isLight)) 0.22f else 0f
    val dim = (settings.wallpaperDim + autoDim).coerceAtMost(0.8f)
    val key = listOf(settings.wallpaperMode, settings.wallpaperPreset, settings.customWallpaperFile, screen, dim)
    val state by produceState(WallpaperState.Empty, key) {
        if (screen.width <= 0 || screen.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            // Render at most at 1440 px width; it is upscaled losslessly for smooth gradients.
            val scale = (1440f / screen.width).coerceAtMost(1f)
            val w = (screen.width * scale).toInt()
            val h = (screen.height * scale).toInt()
            val (bmp, light) = when (settings.wallpaperMode) {
                WallpaperMode.SYSTEM -> null to false
                WallpaperMode.CUSTOM -> {
                    val custom = settings.customWallpaperFile?.let { WallpaperRenderer.loadCustom(File(it), w, h, dim) }
                    (custom ?: WallpaperRenderer.renderPreset(preset, w, h, dim)) to false
                }
                WallpaperMode.PRESET -> WallpaperRenderer.renderPreset(preset, w, h, dim) to preset.isLight
            }
            if (bmp == null) {
                WallpaperState(null, null, screen, false, key)
            } else {
                val blurred = WallpaperRenderer.blurForGlass(bmp)
                // blurScale is relative to screen pixels
                WallpaperState(bmp.asImageBitmap(), blurred.asImageBitmap(), screen, light && dim < 0.2f, key)
            }
        }
    }
    return state
}
