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
