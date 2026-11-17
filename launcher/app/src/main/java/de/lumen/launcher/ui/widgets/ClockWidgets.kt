package de.lumen.launcher.ui.widgets

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lumen.launcher.data.layout.WidgetItem
import de.lumen.launcher.data.layout.WidgetSize
import de.lumen.launcher.data.settings.ClockStyle
import de.lumen.launcher.ui.theme.InterDisplay
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Current time, ticking every second (or aligned to full minutes when seconds are hidden). */
@Composable
fun rememberNow(seconds: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), seconds) {
        while (true) {
            value = System.currentTimeMillis()
            val step = if (seconds) 1000L else 60_000L
            delay(step - (value % step) + 5)
        }
    }
    return now
}

private val widgetText = Color.White

@Composable
fun ClockWidget(item: WidgetItem) {
    val cs = Lumen.settings.clock
    when (cs.style) {
        ClockStyle.DIGITAL -> DigitalClock(item)
        ClockStyle.ANALOG -> if (item.size == WidgetSize.SMALL) AnalogClock(Modifier.fillMaxSize().padding(14.dp)) else AnalogWithDate(item)
    }
}

private fun weightOf(w: Int) = when (w) {
    0 -> FontWeight.Light
    1 -> FontWeight.Medium
    2 -> FontWeight.SemiBold
    else -> FontWeight.Bold
}

@Composable
private fun DigitalClock(item: WidgetItem) {
    val ctx = LocalContext.current
    val cs = Lumen.settings.clock
    val now = rememberNow(cs.showSeconds)
    val use24 = cs.use24h ?: DateFormat.is24HourFormat(ctx)
    val pattern = (if (use24) "H:mm" else "h:mm") + if (cs.showSeconds) ":ss" else ""
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
    val ampm = if (use24) null else SimpleDateFormat("a", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now)
    val big = when (item.size) {
        WidgetSize.SMALL -> 46.sp
        WidgetSize.MEDIUM -> if (cs.showSeconds) 58.sp else 72.sp
        WidgetSize.LARGE -> 96.sp
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (cs.showDate) {
            BasicText(
                date,
                style = LumenType.subhead.copy(color = widgetText.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                time,
                style = LumenType.largeTitle.copy(
                    fontFamily = InterDisplay, fontWeight = weightOf(cs.weight), fontSize = big,
                    lineHeight = big, letterSpacing = (-2).sp, color = widgetText,
                ),
                maxLines = 1,
            )
            if (ampm != null) {
                BasicText(ampm, style = LumenType.headline.copy(color = widgetText.copy(alpha = 0.8f)), modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            }
        }
    }
}

@Composable
private fun AnalogWithDate(item: WidgetItem) {
    val now = rememberNow(false)
    Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        AnalogClock(Modifier.fillMaxHeight().aspectRatio(1f))
        Spacer(Modifier.width(18.dp))
        Column {
            BasicText(SimpleDateFormat("EEEE", Locale.GERMANY).format(now).uppercase(Locale.GERMANY), style = LumenType.footnote.copy(color = androidx.compose.ui.graphics.lerp(Lumen.colors.accent, Color.White, 0.35f), fontWeight = FontWeight.SemiBold))
            BasicText(SimpleDateFormat("d. MMMM", Locale.GERMANY).format(now), style = LumenType.title2.copy(color = widgetText))
        }
    }
}

@Composable
fun AnalogClock(modifier: Modifier) {
    val cs = Lumen.settings.clock
    val accent = Lumen.colors.accent
    val now = rememberNow(cs.showSeconds)
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
    val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    val s = cal.get(Calendar.SECOND).toFloat()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
            val r = min(size.width, size.height) / 2f
            val c = center
            drawCircle(Color.White.copy(alpha = 0.10f), r)
            drawCircle(Color.White.copy(alpha = 0.35f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val a = Math.toRadians(i * 6.0)
                val outer = r * 0.92f
                val inner = if (major) r * 0.80f else r * 0.87f
                drawLine(
                    Color.White.copy(alpha = if (major) 0.9f else 0.35f),
                    Offset(c.x + (inner * sin(a)).toFloat(), c.y - (inner * cos(a)).toFloat()),
                    Offset(c.x + (outer * sin(a)).toFloat(), c.y - (outer * cos(a)).toFloat()),
                    strokeWidth = (if (major) 2.2f else 1f).dp.toPx(), cap = StrokeCap.Round,
                )
            }
            hand(h * 30f, r * 0.5f, 5.dp.toPx(), Color.White)
            hand(m * 6f, r * 0.75f, 3.5.dp.toPx(), Color.White)
            if (cs.showSeconds) hand(s * 6f, r * 0.82f, 1.4.dp.toPx(), accent, tail = r * 0.15f)
            drawCircle(Color.White, 4.dp.toPx())
            drawCircle(if (cs.showSeconds) accent else Color.White, 2.dp.toPx())
        }
    }
}

private fun DrawScope.hand(deg: Float, length: Float, width: Float, color: Color, tail: Float = 0f) {
    rotate(deg, center) {
        drawLine(color, Offset(center.x, center.y + tail), Offset(center.x, center.y - length), strokeWidth = width, cap = StrokeCap.Round)
    }
}

@Composable
fun DateWidget(item: WidgetItem) {
    val now = rememberNow(false)
    val accent = Color(0xFFFF6961)
    when (item.size) {
        WidgetSize.SMALL -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val compact = maxHeight < 120.dp
            val big = if (compact) 40.sp else 64.sp
            Column(Modifier.fillMaxSize()) {
                BasicText(SimpleDateFormat("EEEE", Locale.GERMANY).format(now).uppercase(Locale.GERMANY), style = LumenType.footnote.copy(color = accent, fontWeight = FontWeight.Bold))
                BasicText(
                    SimpleDateFormat("d", Locale.GERMANY).format(now),
                    style = LumenType.largeTitle.copy(fontSize = big, lineHeight = big * 1.04f, fontWeight = FontWeight.Light, color = widgetText),
                )
                Spacer(Modifier.weight(1f))
                BasicText(SimpleDateFormat(if (compact) "MMM yyyy" else "MMMM yyyy", Locale.GERMANY).format(now), style = LumenType.subhead.copy(color = widgetText.copy(alpha = 0.8f)), maxLines = 1)
            }
        }
        else -> Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                BasicText(SimpleDateFormat("EEEE", Locale.GERMANY).format(now).uppercase(Locale.GERMANY), style = LumenType.footnote.copy(color = accent, fontWeight = FontWeight.Bold))
                BasicText(
                    SimpleDateFormat("d", Locale.GERMANY).format(now),
                    style = LumenType.largeTitle.copy(fontSize = 72.sp, lineHeight = 74.sp, fontWeight = FontWeight.Light, color = widgetText),
                )
            }
            Spacer(Modifier.width(20.dp))
            Column {
                BasicText(SimpleDateFormat("MMMM", Locale.GERMANY).format(now), style = LumenType.title2.copy(color = widgetText))
                val cal = Calendar.getInstance(Locale.GERMANY).apply { timeInMillis = now }
                BasicText(
                    "Kalenderwoche ${cal.get(Calendar.WEEK_OF_YEAR)} · Tag ${cal.get(Calendar.DAY_OF_YEAR)}",
                    style = LumenType.footnote.copy(color = widgetText.copy(alpha = 0.7f)),
                )
            }
        }
    }
}
