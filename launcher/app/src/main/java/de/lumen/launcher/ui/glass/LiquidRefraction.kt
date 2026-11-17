package de.lumen.launcher.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import de.lumen.launcher.ui.wallpaper.WallpaperState

/**
 * Lens refraction for liquid-glass surfaces (Android 13+, AGSL RuntimeShader).
 *
 * The blurred wallpaper is sampled through a displacement field derived from the signed distance
 * to the surface's rounded-rectangle outline: the closer to the rim, the stronger the light is bent
 * inwards, with a hint of chromatic dispersion and an edge glow, like a thick piece of glass.
 */
@RequiresApi(33)
object LiquidRefraction {

    private const val SRC = """
        uniform shader img;
        uniform float2 size;
        uniform float2 origin;
        uniform float scale;
        uniform float radius;
        uniform float strength;

        float sdRoundRect(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 xy) {
            float2 c = size * 0.5;
            float2 p = xy - c;
            float d = sdRoundRect(p, c, radius);
            float band = max(min(size.x, size.y) * 0.32, 1.0);
            float edge = clamp(1.0 + d / band, 0.0, 1.0);
            float e = 1.5;
            float2 n = float2(
                sdRoundRect(p + float2(e, 0.0), c, radius) - sdRoundRect(p - float2(e, 0.0), c, radius),
                sdRoundRect(p + float2(0.0, e), c, radius) - sdRoundRect(p - float2(0.0, e), c, radius));
            n = n / max(length(n), 0.0001);
            float bend = edge * edge * edge * strength;
            float2 base = (origin + xy - n * bend) * scale;
            float2 disp = n * bend * 0.10 * scale;
            half r = img.eval(base - disp).r;
            half g = img.eval(base).g;
            half b = img.eval(base + disp).b;
            half3 col = half3(r, g, b);
            col += half3(pow(edge, 4.0) * 0.10);
            return half4(col, 1.0);
        }
    """

    private var shader: RuntimeShader? = null
    private var boundBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(scope: DrawScope, wp: WallpaperState, pos: Offset, path: Path, size: Size) {
        val image = wp.blurred ?: return
        val bmp = image.asAndroidBitmap()
        val rs = shader ?: RuntimeShader(SRC).also { shader = it }
        if (boundBitmap !== bmp) {
            rs.setInputShader("img", BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            boundBitmap = bmp
        }
        val density = scope.density
        rs.setFloatUniform("size", size.width, size.height)
        rs.setFloatUniform("origin", pos.x, pos.y)
        rs.setFloatUniform("scale", wp.blurScale)
        rs.setFloatUniform("radius", minOf(size.width, size.height) * 0.36f)
        rs.setFloatUniform("strength", 18f * density)
        paint.shader = rs
        scope.drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}
