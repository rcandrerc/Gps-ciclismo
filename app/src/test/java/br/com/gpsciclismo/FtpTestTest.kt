package br.com.gpsciclismo

import br.com.gpsciclismo.core.FtpTestTracker
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.TrainingCalendar
import br.com.gpsciclismo.core.WorkoutBlock
import br.com.gpsciclismo.core.WorkoutPlan
import br.com.gpsciclismo.data.RideEntity
import br.com.gpsciclismo.data.RideExport
import br.com.gpsciclismo.data.RidePointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FtpTestTest {

    @Test fun trackerAveragesAndEstimates() {
        val t = FtpTestTracker()
        repeat(600) { t.add(220) }
        repeat(600) { t.add(240) }
        t.add(null) // queda de sinal não entra
        assertEquals(1200, t.sec)
        assertEquals(1, t.noSignalSec)
        assertEquals(230, t.avg)
        assertEquals(219, t.ftp) // 95% de 230 = 218,5
        assertNull(FtpTestTracker().apply { repeat(30) { add(300) } }.ftp)
        assertTrue(FtpTestTracker.complete(1100, 1200))
        assertFalse(FtpTestTracker.complete(900, 1200))
    }

    @Test fun coachDoesNotNagAboveInTestButWarnsBelow() {
        val plan = WorkoutPlan("Teste de FTP", listOf(
            WorkoutBlock(300, PowerTarget.Watts(220), erg = false, ftpTest = true, note = "Começou o teste."),
            WorkoutBlock(300, PowerTarget.Free, erg = false, ftpTest = true),
        ))
        val coach = PowerCoach(plan, PowerCoach.Settings(ftp = 210))
        val said = mutableListOf<String>()
        for (t in 0 until 300) {
            val p = if (t < 150) 280 else if (t < 200) 180 else 230
            said += coach.tick(t, p)
            if (t == 100) {
                assertTrue(coach.state.ftpTest)
                assertFalse(coach.state.erg)
                assertEquals(PowerCoach.Status.IN_ZONE, coach.state.status) // bem acima do alvo é ok
            }
        }
        println(said.joinToString("\n"))
        assertTrue(said.first().contains("220 watts ou mais"))
        assertEquals("Começou o teste.", said[1])
        assertTrue(said.none { it.contains("Baixa") })
        assertTrue(said.any { it.contains("Sobe pra 220") })
    }

    @Test fun ftpTestTemplateIsGuided() {
        // Modelo do teste guiado (fora do calendário publicado, para reusar quando marcar o teste)
        val file = listOf(File("../plano/modelos/teste-ftp-20min.json"), File("plano/modelos/teste-ftp-20min.json")).first { it.exists() }
        val cal = TrainingCalendar.parse(file.readText())
        val day = cal.day(LocalDate.of(2026, 12, 12))!!
        val plan = day.workout!!
        assertTrue(plan.name.startsWith("Teste de FTP"))
        assertEquals(1200, plan.ftpTestSec)
        assertTrue(plan.blocks.filter { it.ftpTest }.none { it.erg })
        // blocos do teste em sequência
        val idx = plan.blocks.indices.filter { plan.blocks[it].ftpTest }
        assertEquals((idx.first()..idx.last()).toList(), idx)
    }

    @Test fun exportCsv() {
        val ride = RideEntity(
            id = 1, uuid = "x", startedAt = 1_765_540_800_000, workoutName = "Teste de FTP 20 min",
            elapsedSec = 2, avgPower = 225, ftpUsed = 210, testAvgPower = 230, testSec = 1200,
            planJson = "{\"name\":\"a\",\n\"blocks\":[]}",
        )
        val pts = listOf(
            RidePointEntity(rideId = 1, time = 1_765_540_801_000, lat = null, lon = null, altitude = null,
                speedKmh = 35.25, power = 230, heartRate = 160, cadence = 92, block = 9),
            RidePointEntity(rideId = 1, time = 1_765_540_802_000, lat = -23.5, lon = -46.6, altitude = 760.0,
                speedKmh = null, power = null, heartRate = 161, cadence = null, block = null),
        )
        val csv = RideExport.csv(ride, pts)
        println(csv)
        assertTrue(csv.contains("# teste_ftp_estimado_w: 219\n"))
        assertTrue(csv.contains("# plano_json: {\"name\":\"a\", \"blocks\":[]}\n"))
        val rows = csv.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("segundo,hora,bloco,potencia_w,fc_bpm,cadencia_rpm,velocidade_kmh,lat,lon,altitude_m", rows[0])
        assertTrue(rows[1].startsWith("0,"))
        assertTrue(rows[1].endsWith(",10,230,160,92,35.3,,,") || rows[1].endsWith(",10,230,160,92,35.2,,,"))
        assertTrue(rows[2].endsWith(",,,161,,,-23.500000,-46.600000,760.0"))
        assertNotNull(RideExport.fileName(ride).takeIf { it.endsWith("_teste-de-ftp-20-min.csv") })
    }

    @Test fun intensityOffsetShiftsTargetsAndErg() {
        val plan = WorkoutPlan("x", listOf(
            WorkoutBlock(300, PowerTarget.Watts(200)),
            WorkoutBlock(300, PowerTarget.Zone(2)),
            WorkoutBlock(300, PowerTarget.Free),
        ))
        val coach = PowerCoach(plan, PowerCoach.Settings(ftp = 200))
        coach.tick(0, 200)
        assertEquals(200, coach.state.ergWatts)
        coach.adjust(-10)
        coach.tick(1, 190)
        assertEquals(PowerTarget.Watts(190), coach.state.target)
        assertEquals(190, coach.state.ergWatts)
        assertEquals(PowerCoach.Status.IN_ZONE, coach.state.status)
        coach.tick(300, 120)
        assertEquals(120, coach.state.ergWatts) // Z2 = 65% de 200 = 130, menos 10
        assertEquals(100..140, coach.state.range) // 110..150 deslocado
        coach.tick(600, 150)
        assertNull(coach.state.ergWatts)
    }
}
