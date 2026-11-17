package de.lumen.launcher

import de.lumen.launcher.system.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class RelativeTimeTest {
    private val now = Calendar.getInstance().apply { set(2026, 10, 17, 15, 0, 0) }.timeInMillis

    @Test fun formats() {
        assertEquals("jetzt", RelativeTime.format(now - 20_000, now))
        assertEquals("vor 5 Min.", RelativeTime.format(now - 5 * 60_000, now))
        assertEquals("12:30", RelativeTime.format(now - (2 * 60 + 30) * 60_000L, now))
        assertEquals("gestern", RelativeTime.format(now - 20 * 3600_000L, now))
    }
}
