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

    @Test fun lateWorkoutCountsForPlannedDay() {
        // Treino "B" de 22 feito no dia 23 (um dia depois): conta para o dia 22, não é furo.
        val rides = mapOf(d(23) to listOf(DoneRide(1000, 80, "B")))
        val assigned = PlanCompliance.assign(cal, rides, { it.workoutName })
        assertEquals(listOf(DoneRide(1000, 80, "B")), assigned[d(22)])
        assertNull(assigned[d(23)])
        val s = PlanCompliance.summary(cal, rides, today)
        assertEquals(1, s.done)
        assertEquals(3, s.missed) // 20, 24 e 26 continuam sem treino
    }

    @Test fun rideOnItsOwnDayIsNotMoved() {
        // "C" feito no dia certo e outro "C" no dia 25: o do dia 25 não rouba nada.
        val rides = mapOf(d(24) to listOf(DoneRide(1000, 80, "C")), d(25) to listOf(DoneRide(1000, 80, "C")))
        val assigned = PlanCompliance.assign(cal, rides, { it.workoutName })
        assertEquals(1, assigned[d(24)]!!.size)
        assertEquals(1, assigned[d(25)]!!.size)
    }

    @Test fun manualDayWins() {
        // Treino "B" (de 22) feito em 23, mas o atleta mandou contar no próprio dia 23.
        val r = DoneRide(1000, 80, "B", planDate = d(23))
        val assigned = PlanCompliance.assign(cal, mapOf(d(23) to listOf(r)), { it.workoutName }, { it.planDate })
        assertNull(assigned[d(22)])
        assertEquals(listOf(r), assigned[d(23)])
        // E pode mandar para um dia planejado qualquer, mesmo com nome diferente.
        val r2 = DoneRide(1000, 80, "Pedal livre", planDate = d(20))
        val a2 = PlanCompliance.assign(cal, mapOf(d(21) to listOf(r2)), { it.workoutName }, { it.planDate })
        assertEquals(listOf(r2), a2[d(20)])
        assertEquals(DayStatus.DONE, PlanCompliance.status(cal.day(d(20)), a2[d(20)]!!, d(20), today))
    }
}
