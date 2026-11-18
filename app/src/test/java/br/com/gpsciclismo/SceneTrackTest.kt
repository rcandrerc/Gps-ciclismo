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

    @Test fun strongBlockIsClimbRecoveryIsDescent() {
        val t = SceneTrack.of(plan, 210)
        assertTrue(t.gradeAt(500.0) > 5.0)          // 205 W com FTP 210: subida
        assertTrue(t.gradeAt(870.0) < 0.0)          // 110 W: descida leve
        assertEquals(1, t.blockAt(400.0))
        assertEquals(4, t.zones[1])
        assertEquals(960, t.totalSec)
    }

    @Test fun gradeChangesSmoothlyAtBlockStart() {
        val t = SceneTrack.of(plan, 210)
        val before = t.gradeAt(299.9); val after = t.gradeAt(300.1)
        assertTrue(kotlin.math.abs(after - before) < 0.5)
    }

    @Test fun elevationRisesOnClimbAndStaysFlatAfterEnd() {
        val t = SceneTrack.of(plan, 210)
        assertTrue(t.elevation(700.0) > t.elevation(400.0))
        assertEquals(t.elevation(960.0), t.elevation(2000.0), 1e-9)
    }

    @Test fun offsetMakesClimbSteeper() {
        val base = SceneTrack.of(plan, 210).gradeAt(500.0)
        val harder = SceneTrack.of(plan, 210, offsetW = 20).gradeAt(500.0)
        assertTrue(harder > base)
    }

    @Test fun freeRideRollsGently() {
        val t = SceneTrack.of(null, 210)
        val g = (0..600).map { t.gradeAt(it.toDouble()) }
        assertTrue(g.max() < 5.0 && g.min() > -5.0)
        assertTrue(g.max() > 1.0)
    }
}
