package br.com.gpsciclismo

import br.com.gpsciclismo.core.WeightLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WeightLogTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")
    private val day = 24L * 3600 * 1000

    @Test fun parsesCommaAndDot() {
        assertEquals(82.4, WeightLog.parse("82,4")!!, 0.0)
        assertEquals(82.4, WeightLog.parse(" 82.4 ")!!, 0.0)
        assertEquals(82.5, WeightLog.parse("82,46")!!, 0.0)
        assertNull(WeightLog.parse("8,2"))
        assertNull(WeightLog.parse(""))
        assertNull(WeightLog.parse("abc"))
    }

    @Test fun sameDayUsesLocalTimeZone() {
        // 26/11/2026 23:30 e 27/11/2026 00:30 em São Paulo (UTC-3)
        val late = 1_795_746_600_000L // 2026-11-27T02:30Z
        assertFalse(WeightLog.sameDay(late, late + 3600_000, sp))
        assertTrue(WeightLog.sameDay(late, late - 3600_000, sp))
        assertFalse(WeightLog.sameDay(null, late, sp))
    }

    @Test fun wattsPerKg() {
        assertEquals(2.6, WeightLog.wattsPerKg(210, 81.0)!!, 0.0)
        assertNull(WeightLog.wattsPerKg(210, 0.0))
        assertNull(WeightLog.wattsPerKg(null, 80.0))
    }

    @Test fun changeOverPeriod() {
        val now = 100 * day
        val e = listOf(now - 40 * day to 84.0, now - 8 * day to 83.0, now - 2 * day to 82.6, now to 82.2)
        assertEquals(-0.8, WeightLog.change(e, 7, now)!!, 0.001)
        assertEquals(-1.8, WeightLog.change(e, 30, now)!!, 0.001)
        assertNull(WeightLog.change(e, 60, now))
    }
}
