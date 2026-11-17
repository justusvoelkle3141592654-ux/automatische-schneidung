package de.lumen.launcher.data.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import de.lumen.launcher.core.Squircle
import de.lumen.launcher.data.settings.IconStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class IconSpec(
    val sizePx: Int,
    val style: IconStyle = IconStyle.DEFAULT,
    val iconPack: String? = null,
    val maskIconPack: Boolean = false,
    val accent: Int = 0xFF0A84FF.toInt(),
)

/** A rendered icon. When [glassPlate] is true the UI draws a glass plate behind the glyph. */
@Immutable
class IconImage(val bitmap: ImageBitmap, val glassPlate: Boolean)

/**
 * Renders app icons into uniform, pre-masked bitmaps.
 *
 * Masking happens once at load time (not every frame), which keeps scrolling cheap.
 * Adaptive icons are drawn full-bleed from their individual layers so they fill the
 * superellipse exactly like native iOS icons.
 */
class IconLoader(
    private val context: Context,
    private val repo: AppRepository,
    private val iconPacks: IconPackManager,
) {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(64L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, IconImage>(maxBytes) {
        override fun sizeOf(key: String, value: IconImage): Int =
            value.bitmap.width * value.bitmap.height * 4
    }

    private val io = Dispatchers.IO.limitedParallelism(4)

    private fun cacheKey(key: AppKey, spec: IconSpec) = "${key.value}|${spec.hashCode()}"

    fun peek(key: AppKey, spec: IconSpec): IconImage? = cache.get(cacheKey(key, spec))

    fun clear() {
        cache.evictAll()
        iconPacks.invalidate()
    }

    suspend fun load(app: AppInfo, spec: IconSpec): IconImage? {
        val ck = cacheKey(app.key, spec)
        cache.get(ck)?.let { return it }
        return withContext(io) {
            cache.get(ck) ?: render(app, spec)?.also { cache.put(ck, it) }
        }
    }

    /** Renders an arbitrary drawable (e.g. a shortcut icon) with the same visual treatment. */
    fun renderDrawable(drawable: Drawable, spec: IconSpec): IconImage =
        compose(drawable, spec, fromPack = false)

    /** Test hook: supplies icons when no LauncherActivityInfo exists (JVM screenshot tests). */
    @androidx.annotation.VisibleForTesting
    var testIconProvider: ((AppInfo) -> Drawable?)? = null

    private fun render(app: AppInfo, spec: IconSpec): IconImage? {
        val density = context.resources.displayMetrics.densityDpi
        val packIcon = spec.iconPack?.let { iconPacks.iconFor(it, app.packageName, app.className, density) }
        val drawable = packIcon ?: repo.activityInfo(app.key)?.getIcon(density) ?: testIconProvider?.invoke(app) ?: return null
        return compose(drawable, spec, fromPack = packIcon != null)
    }

    private fun compose(drawable: Drawable, spec: IconSpec, fromPack: Boolean): IconImage {
        val s = spec.sizePx.coerceAtLeast(16)
        if (fromPack && !spec.maskIconPack) {
            // Keep the icon pack's own shape.
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, s, s)
            drawable.draw(Canvas(bmp))
            return IconImage(bmp.asImageBitmap(), glassPlate = false)
        }
        return when (spec.style) {
            IconStyle.DEFAULT -> IconImage(mask(fullBleed(drawable, s)), false)
            IconStyle.DARK -> IconImage(mask(darkVariant(drawable, s)), false)
            IconStyle.TINTED -> IconImage(mask(tintedVariant(drawable, s, spec.accent)), false)
            IconStyle.CLEAR -> IconImage(clearVariant(drawable, s).asImageBitmap(), true)
        }
    }

    // ---------------------------------------------------------------- rendering helpers

    private fun drawAdaptiveLayer(layer: Drawable?, c: Canvas, s: Int) {
        layer ?: return
        val inset = s / 4 // 108dp layer, 72dp visible: layer = 1.5 × visible size
        layer.setBounds(-inset, -inset, s + inset, s + inset)
        layer.draw(c)
    }

    private fun fullBleed(d: Drawable, s: Int): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            drawAdaptiveLayer(d.background, c, s)
            drawAdaptiveLayer(d.foreground, c, s)
            return bmp
        }
        // Legacy icon: if it is already a full square-ish image use it edge to edge,
        // otherwise place it on a white plate like iOS does for non-conforming artwork.
        val probe = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(probe))
        if (isOpaqueAtInset(probe, 0.1f)) {
            val m = (s * 0.06f).toInt() // slight zoom removes baked-in rounded corners
            d.setBounds(-m, -m, s + m, s + m)
            d.draw(c)
        } else {
            c.drawColor(0xFFFFFFFF.toInt())
            val pad = (s * 0.16f).toInt()
            d.setBounds(pad, pad, s - pad, s - pad)
            d.draw(c)
        }
        probe.recycle()
        return bmp
    }

    /**
     * True for square / rounded-square artwork: opaque near the edge midpoints AND near the
     * corners (13% inset on the diagonal lies outside a circle but inside a rounded square).
     */
    private fun isOpaqueAtInset(b: Bitmap, f: Float): Boolean {
        val w = b.width
        val m = w / 2
        val i = (w * f * 0.6f).toInt()
        val ie = w - 1 - i
        val k = (w * 0.13f).toInt()
        val ke = w - 1 - k
        val pts = listOf(i to m, ie to m, m to i, m to ie, k to k, ke to ke, k to ke, ke to k)
        return pts.all { (x, y) -> (b.getPixel(x, y) ushr 24) > 0xF0 }
    }

    private fun glyph(d: Drawable, s: Int): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            val mono = if (Build.VERSION.SDK_INT >= 33) d.monochrome else null
            if (mono != null) {
                mono.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                drawAdaptiveLayer(mono, c, s)
                mono.colorFilter = null
                return bmp
            }
            val fg = d.foreground
            fg?.colorFilter = ColorMatrixColorFilter(liftedGray())
            drawAdaptiveLayer(fg, c, s)
            fg?.colorFilter = null
            return bmp
        }
        val pad = (s * 0.18f).toInt()
        d.colorFilter = ColorMatrixColorFilter(liftedGray())
        d.setBounds(pad, pad, s - pad, s - pad)
        d.draw(c)
        d.colorFilter = null
        return bmp
    }

    /** Grayscale with lifted shadows so glyphs read as light marks. */
    private fun liftedGray() = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            0.6f, 0f, 0f, 0f, 100f,
            0f, 0.6f, 0f, 0f, 100f,
            0f, 0f, 0.6f, 0f, 100f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    private fun darkPlate(c: Canvas, s: Int, tint: Int? = null) {
        val top = tint?.let { ColorUtils.blendARGB(0xFF2C2C2E.toInt(), it, 0.18f) } ?: 0xFF3A3A3C.toInt()
        val bottom = tint?.let { ColorUtils.blendARGB(0xFF141416.toInt(), it, 0.10f) } ?: 0xFF161618.toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
    }

    private fun darkVariant(d: Drawable, s: Int): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            darkPlate(c, s)
            drawAdaptiveLayer(d.foreground, c, s)
        } else {
            val base = fullBleed(d, s)
            c.drawBitmap(base, 0f, 0f, null)
            base.recycle()
            c.drawColor(0x66000000)
        }
        return bmp
    }

    private fun tintedVariant(d: Drawable, s: Int, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        darkPlate(c, s, accent)
        val g = glyph(d, s)
        val light = ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.45f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = PorterDuffColorFilter(light, PorterDuff.Mode.MULTIPLY)
        c.drawBitmap(g, 0f, 0f, p)
        g.recycle()
        return bmp
    }

    private fun clearVariant(d: Drawable, s: Int): Bitmap {
        val g = glyph(d, s)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.alpha = 235
        Canvas(out).drawBitmap(g, 0f, 0f, p)
        g.recycle()
        return out
    }

    /** Applies the superellipse mask with anti-aliased edges. */
    private fun mask(src: Bitmap): ImageBitmap {
        val s = src.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawPath(Squircle.androidPath(s.toFloat(), s.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        paint.isFilterBitmap = true
        c.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out.asImageBitmap()
    }
}
