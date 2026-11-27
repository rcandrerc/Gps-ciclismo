package br.com.gpsciclismo

import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.SceneTrack
import br.com.gpsciclismo.core.WorkoutBlock
import br.com.gpsciclismo.core.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTrackTest {
    private val plan = WorkoutPlan(
        "Limiar", listOf(
            WorkoutBlock(300, PowerTarget.Zone(2)),
            WorkoutBlock(480, PowerTarget.Watts(205)),
            WorkoutBlock(180, PowerTarget.Watts(110)),
        )
    )

    @Test fun blocksAndZones() {
        val t = SceneTrack.of(plan, 210)
        assertEquals(1, t.blockAt(400.0))
        assertEquals(4, t.zones[1])
        assertEquals(960, t.totalSec)
        assertEquals(215, SceneTrack.of(plan, 210, offsetW = 10).watts[1])
    }

    @Test fun easyIsUprightHardIsAttack() {
        assertEquals(0f, SceneTrack.postureFor(110f, 200), 1e-6f)   // Z1
        assertEquals(0f, SceneTrack.postureFor(130f, 200), 1e-6f)   // fim da Z2
        assertEquals(1f, SceneTrack.postureFor(260f, 200), 1e-6f)   // Z6
        assertEquals(1f, SceneTrack.postureFor(600f, 200), 1e-6f)
        assertEquals(0f, SceneTrack.postureFor(0f, 200), 1e-6f)
        assertEquals(0f, SceneTrack.postureFor(200f, 0), 1e-6f)
    }

    @Test fun postureGrowsWithZone() {
        val z3 = SceneTrack.postureFor(165f, 200)
        val z4 = SceneTrack.postureFor(200f, 200)
        val z5 = SceneTrack.postureFor(230f, 200)
        assertTrue(z3 > 0f && z3 < z4 && z4 < z5 && z5 < 1f)
        var last = -1f
        for (w in 0..400) {
            val p = SceneTrack.postureFor(w.toFloat(), 200)
            assertTrue(p >= last)
            last = p
        }
    }
}
