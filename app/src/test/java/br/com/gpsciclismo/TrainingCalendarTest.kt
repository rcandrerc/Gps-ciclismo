package br.com.gpsciclismo

import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.TrainingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class TrainingCalendarTest {
    private val sample = """
        {"name":"Teste","days":[
          {"date":"2026-11-14","where":"rolo","workout":{"name":"Z2","blocks":[
            {"durationSec":300,"target":{"type":"zone","zone":2}},
            {"durationSec":60,"target":{"type":"watts","watts":200}},
            {"durationSec":60,"target":{"type":"free"}}]}},
          {"date":"2026-11-15","title":"Descanso","extra":"ignorado"},
          {"date":"data-ruim"},
          {"date":"2026-11-17","workout":{"name":"Sweet spot","blocks":[{"durationSec":600,"target":{"type":"watts","watts":190}}]}}
        ]}
    """.trimIndent()

    @Test fun parsesDaysAndRest() {
        val cal = TrainingCalendar.parse(sample)
        val d = cal.day(LocalDate.of(2026, 11, 14))!!
        assertEquals("Z2", d.displayTitle())
        assertEquals(420, d.workout!!.totalSec)
        assertEquals(PowerTarget.Free, d.workout!!.blocks[2].target)
        assertTrue(cal.day(LocalDate.of(2026, 11, 15))!!.isRest)
        assertNull(cal.day(LocalDate.of(2026, 11, 16)))
    }

    @Test fun nextWorkoutSkipsRest() {
        val cal = TrainingCalendar.parse(sample)
        assertEquals("Sweet spot", cal.next(LocalDate.of(2026, 11, 15))!!.displayTitle())
        assertNull(cal.next(LocalDate.of(2026, 11, 18)))
    }

    @Test fun publishedPlanIsValid() {
        // O arquivo que o app baixa do repositório precisa sempre ser válido.
        val file = listOf(File("../plano/calendario.json"), File("plano/calendario.json")).first { it.exists() }
        val cal = TrainingCalendar.parse(file.readText())
        assertTrue(cal.days.isNotEmpty())
        cal.days.forEach { assertNotNull("data inválida: ${it.date}", it.localDate()) }
    }
}
