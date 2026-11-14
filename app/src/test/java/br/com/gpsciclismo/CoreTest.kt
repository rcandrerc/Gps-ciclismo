package br.com.gpsciclismo

import br.com.gpsciclismo.core.BleParsers
import br.com.gpsciclismo.core.CadenceCalculator
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.WorkoutBlock
import br.com.gpsciclismo.core.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun heartRate8and16bit() {
        assertEquals(72, BleParsers.heartRate(bytes(0x00, 72)))
        assertEquals(300, BleParsers.heartRate(bytes(0x01, 0x2C, 0x01)))
    }

    @Test fun powerWithCrankData() {
        // flags: crank present (0x20) + pedal balance (0x01); 250 W; balance; crank revs=10, time=2048
        val m = BleParsers.cyclingPower(bytes(0x21, 0x00, 0xFA, 0x00, 0x32, 0x0A, 0x00, 0x00, 0x08))!!
        assertEquals(250, m.watts)
        assertEquals(10, m.crank!!.cumulativeRevs)
        assertEquals(2048, m.crank!!.lastEventTime1024)
    }

    @Test fun cscCrankOnly() {
        val m = BleParsers.csc(bytes(0x02, 0x05, 0x00, 0x00, 0x04))!!
        assertEquals(5, m.crank!!.cumulativeRevs)
        assertEquals(null, m.wheel)
    }

    @Test fun cadenceHandlesRollover() {
        val c = CadenceCalculator()
        c.update(BleParsers.CrankData(65535, 65000), 0)
        // 1 volta em 1024/1024 s = 60 rpm, com estouro dos dois contadores
        val rpm = c.update(BleParsers.CrankData(0, (65000 + 1024) and 0xFFFF), 1000)
        assertEquals(60, rpm)
    }

    @Test fun cogganZones() {
        assertEquals(110..150, CogganZones.range(2, 200))
        assertEquals(4, CogganZones.zoneFor(200, 200))
    }

    @Test fun coachAnnouncesAndAlerts() {
        val plan = WorkoutPlan("teste", listOf(
            WorkoutBlock(300, PowerTarget.Watts(240)),
            WorkoutBlock(300, PowerTarget.Watts(130)),
        ))
        val coach = PowerCoach(plan, PowerCoach.Settings(ftp = 250))
        val said = mutableListOf<Pair<Int, String>>()
        for (t in 0 until 700) {
            val p = when {
                t < 60 -> 240
                t < 80 -> 200 // abaixo por 20 s
                t < 300 -> 240
                t < 400 -> 180 // acima no bloco de 130
                else -> 130
            }
            coach.tick(t, p).forEach { said += t to it }
        }
        val text = said.joinToString("\n") { "${it.first}: ${it.second}" }
        println(text)
        assertTrue(said.first().second.startsWith("Bloco 1 de 2. 5 minutos a 240 watts"))
        assertNotNull(said.find { it.first == 65 && it.second.contains("Sobe pra 240") })
        assertNotNull(said.find { it.second.contains("Boa, na zona") })
        assertNotNull(said.find { it.second.contains("Próximo: 5 minutos a 130 watts") })
        assertNotNull(said.find { it.second.contains("Baixa pra 130") })
        assertTrue(said.last().second.contains("Treino concluído"))
    }

    @Test fun rollingAverageUsesExactlyNSeconds() {
        val avg = br.com.gpsciclismo.core.RollingAverage(3)
        // amostras a cada segundo, com um pouco de atraso no relógio
        listOf(0L to 100, 1003L to 200, 1998L to 300, 3004L to 400).forEach { (t, w) -> avg.add(t, w) }
        assertEquals(300, avg.average(3004)) // só 200, 300, 400
        val five = br.com.gpsciclismo.core.RollingAverage(5)
        (0 until 10).forEach { five.add(it * 1000L, if (it % 2 == 0) 100 else 300) }
        assertEquals(220, five.average(9000)) // 300,100,300,100,300 = 220
        assertEquals(null, br.com.gpsciclismo.core.RollingAverage(3).average(0))
        assertEquals(null, avg.average(10_000)) // sensor parou de mandar
    }

    @Test fun powerColorStatus() {
        assertEquals(PowerCoach.Status.IN_ZONE, PowerCoach.compare(240, 228..252))
        assertEquals(PowerCoach.Status.BELOW, PowerCoach.compare(200, 228..252))
        assertEquals(PowerCoach.Status.ABOVE, PowerCoach.compare(260, 228..252))
        assertEquals(PowerCoach.Status.NO_TARGET, PowerCoach.compare(260, null))
    }
}
