package br.com.gpsciclismo

import br.com.gpsciclismo.core.Ftms
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.RideStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun indoorBikeDataSpeedCadencePower() {
        // flags 0x0044: velocidade (bit0 = 0) + cadência (bit2) + potência (bit6)
        // 32,50 km/h = 3250 = 0x0CB2; 90 rpm = 180 meio-rpm = 0xB4; 245 W = 0xF5
        val d = Ftms.indoorBikeData(bytes(0x44, 0x00, 0xB2, 0x0C, 0xB4, 0x00, 0xF5, 0x00))!!
        assertEquals(32.5, d.speedKmh!!, 0.001)
        assertEquals(90, d.cadence)
        assertEquals(245, d.power)
    }

    @Test fun indoorBikeDataSkipsOptionalFields() {
        // velocidade + distância total (bit4, 3 bytes) + resistência (bit5) + potência (bit6)
        val d = Ftms.indoorBikeData(bytes(0x70, 0x00, 0xE8, 0x03, 0x10, 0x27, 0x00, 0x05, 0x00, 0xC8, 0x00))!!
        assertEquals(10.0, d.speedKmh!!, 0.001)
        assertNull(d.cadence)
        assertEquals(200, d.power)
    }

    @Test fun indoorBikeDataMoreDataHasNoSpeed() {
        val d = Ftms.indoorBikeData(bytes(0x41, 0x00, 0x64, 0x00))!!
        assertNull(d.speedKmh)
        assertEquals(100, d.power)
    }

    @Test fun commands() {
        assertArrayEquals(bytes(0x05, 0xF0, 0x00), Ftms.setTargetPower(240))
        assertArrayEquals(bytes(0x05, 0x2C, 0x01), Ftms.setTargetPower(300))
        assertArrayEquals(bytes(0x00), Ftms.requestControl())
        assertEquals(0x11, Ftms.flatRoad()[0].toInt())
    }

    @Test fun controlResponse() {
        val r = Ftms.response(bytes(0x80, 0x00, 0x01))!!
        assertEquals(Ftms.OP_REQUEST_CONTROL, r.requestOp)
        assertTrue(r.ok)
        assertEquals(false, Ftms.response(bytes(0x80, 0x05, 0x03))!!.ok)
        assertNull(Ftms.response(bytes(0x05, 0x00)))
    }

    @Test fun ergWattsFromTarget() {
        assertEquals(240, PowerTarget.Watts(240).ergWatts(250))
        assertEquals(245, PowerTarget.Zone(4).ergWatts(250)) // 98% do FTP
        assertEquals(162, PowerTarget.Zone(2).ergWatts(250)) // 65%
        assertNull(PowerTarget.Free.ergWatts(250))
    }

    @Test fun indoorDistanceFromTrainerSpeed() {
        val s = RideStats()
        repeat(60) { s.addIndoorSecond(36.0) } // 36 km/h = 10 m/s
        s.addIndoorSecond(null)
        assertEquals(600.0, s.distanceM, 0.01)
        assertEquals(36.0, s.maxSpeedKmh, 0.01)
    }
}
