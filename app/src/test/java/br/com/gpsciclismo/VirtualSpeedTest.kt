package br.com.gpsciclismo

import br.com.gpsciclismo.core.VirtualSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSpeedTest {
    @Test fun zeroWattsIsStopped() {
        assertEquals(0.0, VirtualSpeed.kmh(0, 80.0), 0.001)
    }

    @Test fun typicalPowersGiveRoadSpeeds() {
        val v150 = VirtualSpeed.kmh(150, 80.0)
        val v200 = VirtualSpeed.kmh(200, 80.0)
        assertTrue("150 W = $v150", v150 in 27.0..32.0)
        assertTrue("200 W = $v200", v200 in 30.0..35.0)
        assertTrue(v200 > v150)
    }

    @Test fun heavierRiderIsSlower() {
        assertTrue(VirtualSpeed.kmh(200, 95.0) < VirtualSpeed.kmh(200, 65.0))
    }
}
