package br.com.gpsciclismo

import br.com.gpsciclismo.core.DashboardLayout
import br.com.gpsciclismo.core.LiveField
import br.com.gpsciclismo.core.TileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutTest {
    @Test fun encodeDecodeRoundTrip() {
        val l = DashboardLayout.DEFAULT
        assertEquals(l, DashboardLayout.decode(l.encode()))
        assertEquals(DashboardLayout.DEFAULT, DashboardLayout.decode(null))
    }

    @Test fun editOperations() {
        var l = DashboardLayout.DEFAULT
        l = l.remove(0) // tira TEMPO
        assertEquals(LiveField.DISTANCE, l.tiles[0].field)
        l = l.move(0, 1)
        assertEquals(LiveField.SPEED, l.tiles[0].field)
        l = l.resize(0) // BIG -> SMALL
        assertEquals(TileSize.SMALL, l.tiles[0].size)
        l = l.add(LiveField.KCAL)
        assertEquals(LiveField.KCAL, l.tiles.last().field)
        assertEquals(true, LiveField.TIME in l.available())
    }

    @Test fun rowsPackSmallTilesInPairs() {
        val rows = DashboardLayout.DEFAULT.rows().map { r -> r.map { it.value.field } }
        assertEquals(listOf(LiveField.TIME, LiveField.DISTANCE), rows[0])
        assertEquals(listOf(LiveField.SPEED), rows[1])
        assertEquals(5, rows.size)
    }
}

class RideStatsTest {
    @Test fun gpsDriftWhileStoppedDoesNotInflateAverage() {
        val s = br.com.gpsciclismo.core.RideStats()
        // parado: GPS "passeia" ~10 m a cada leitura com velocidade baixa
        repeat(30) { i ->
            s.addLocation(-23.0 + (i % 2) * 0.0001, -46.0, 0.5)
            s.tickSecond(0.5, null, null, null)
        }
        assertEquals(0.0, s.distanceM, 0.001)
        assertEquals(0.0, s.avgSpeedKmh, 0.001)
    }

    @Test fun tabletPacksMoreTilesPerRow() {
        val rows = DashboardLayout.DEFAULT.rows(perRow = 4).map { r -> r.map { it.value.field } }
        assertEquals(listOf(LiveField.TIME, LiveField.DISTANCE), rows[0])
        assertEquals(listOf(LiveField.SPEED), rows[1])
        assertEquals(4, rows[2].size)
        assertEquals(4, rows.size)
    }
}
