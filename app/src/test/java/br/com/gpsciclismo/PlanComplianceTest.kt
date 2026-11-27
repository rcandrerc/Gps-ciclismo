package br.com.gpsciclismo

import br.com.gpsciclismo.core.DayStatus
import br.com.gpsciclismo.core.DoneRide
import br.com.gpsciclismo.core.PlanCompliance
import br.com.gpsciclismo.core.TrainingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PlanComplianceTest {
    // 20, 22, 24, 26, 27: treinos de 1000 s; 21: descanso.
    private val cal = TrainingCalendar.parse(
        """{"days":[
          {"date":"2026-11-20","workout":{"name":"A","blocks":[{"durationSec":1000,"target":{"type":"zone","zone":4}}]}},
          {"date":"2026-11-21","title":"Descanso"},
          {"date":"2026-11-22","workout":{"name":"B","blocks":[{"durationSec":1000,"target":{"type":"zone","zone":4}}]}},
          {"date":"2026-11-24","workout":{"name":"C","blocks":[{"durationSec":1000,"target":{"type":"zone","zone":4}}]}},
          {"date":"2026-11-26","workout":{"name":"D","blocks":[{"durationSec":1000,"target":{"type":"zone","zone":4}}]}},
          {"date":"2026-11-27","workout":{"name":"E","blocks":[{"durationSec":1000,"target":{"type":"zone","zone":4}}]}}
        ]}"""
    )
    private val today = LocalDate.of(2026, 11, 27)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    private fun st(day: Int, vararg rides: DoneRide) =
        PlanCompliance.status(cal.day(d(day)), rides.toList(), d(day), today)

    @Test fun statusPerDay() {
        assertEquals(DayStatus.DONE, st(20, DoneRide(990, 90)))
        assertEquals(DayStatus.PARTIAL, st(20, DoneRide(500, 95)))
        assertEquals(DayStatus.PARTIAL, st(20, DoneRide(1000, 30)))
        assertEquals(DayStatus.DONE, st(20, DoneRide(500, 80), DoneRide(500, null)))
        assertEquals(DayStatus.MISSED, st(22))
        assertEquals(DayStatus.REST, st(21))
        assertEquals(DayStatus.EXTRA, st(21, DoneRide(3600, null)))
        assertEquals(DayStatus.NONE, st(23))
        assertEquals(DayStatus.PLANNED, st(27)) // hoje ainda não é furo
    }

    @Test fun summaryCountsHalfForPartialAndIgnoresRestAndToday() {
        val rides = mapOf(
            d(20) to listOf(DoneRide(1000, 90)),
            d(21) to listOf(DoneRide(3000, null)), // extra no descanso não conta
            d(24) to listOf(DoneRide(400, 90)),    // parcial
            d(26) to listOf(DoneRide(1000, 70)),
        )
        val s = PlanCompliance.summary(cal, rides, today)
        assertEquals(4, s.planned) // 20, 22, 24, 26 (27 ainda pendente)
        assertEquals(2, s.done)
        assertEquals(1, s.partial)
        assertEquals(1, s.missed)
        assertEquals(62, s.ratePct)
    }

    @Test fun noPlanNoRate() {
        val s = PlanCompliance.summary(null, emptyMap(), today)
        assertNull(s.ratePct)
        assertEquals(0, s.planned)
    }
}
