package br.com.gpsciclismo.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * O "relevo" da cena 2D: a estrada é o próprio treino.
 * Cada bloco vira uma inclinação (bloco forte = subida, recuperação = descida leve),
 * indexada pelo tempo do treino em segundos. Sem treino (pedal livre) vira um sobe e desce suave.
 * Não depende de Android, para dar para testar.
 */
class SceneTrack private constructor(
    /** Início de cada bloco, em segundos do treino. */
    val starts: IntArray,
    val durations: IntArray,
    /** Watts alvo de cada bloco (null = livre). */
    val watts: Array<Int?>,
    /** Zona do Coggan de cada bloco (0 = livre). */
    val zones: IntArray,
    private val grades: DoubleArray,
    private val free: Boolean,
) {
    val totalSec: Int = if (starts.isEmpty()) 0 else starts.last() + durations.last()
    val blockCount: Int get() = starts.size

    // Elevação acumulada (inclinação % × segundos) a cada segundo, para o desenho ser barato.
    private val cumulative: DoubleArray = if (free) DoubleArray(0) else DoubleArray(totalSec + 1).also { c ->
        for (i in 1..totalSec) c[i] = c[i - 1] + gradeAt(i - 0.5)
    }

    fun blockAt(t: Double): Int {
        if (starts.isEmpty()) return -1
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Inclinação em % no instante t (transição suave de [BLEND_SEC] s entre blocos). */
    fun gradeAt(t: Double): Double {
        if (free) return FREE_A * sin(t / FREE_P1) + FREE_B * sin(t / FREE_P2)
        if (starts.isEmpty() || t < 0 || t >= totalSec) return 0.0
        val i = blockAt(t)
        val start = starts[i]; val end = start + durations[i]
        val half = BLEND_SEC / 2
        val prev = if (i > 0) grades[i - 1] else 0.0
        val next = if (i < grades.size - 1) grades[i + 1] else 0.0
        val g = grades[i]
        return when {
            t - start < half -> mix(prev, g, smooth((t - start + half) / BLEND_SEC))
            end - t < half -> mix(g, next, smooth((t - (end - half)) / BLEND_SEC))
            else -> g
        }
    }

    /** Elevação (unidade: % × s) no instante t. Só a diferença entre dois instantes importa. */
    fun elevation(t: Double): Double {
        if (free) return -FREE_A * FREE_P1 * cos(t / FREE_P1) - FREE_B * FREE_P2 * cos(t / FREE_P2)
        if (t <= 0) return 0.0
        if (t >= totalSec) return cumulative[totalSec]
        val i = t.toInt()
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * (t - i)
    }

    companion object {
        const val BLEND_SEC = 8.0
        const val MIN_GRADE = -2.5
        const val MAX_GRADE = 9.0
        private const val FREE_A = 2.6
        private const val FREE_B = 1.4
        private const val FREE_P1 = 70.0
        private const val FREE_P2 = 23.0

        /** Inclinação de um bloco pela intensidade: ~60% do FTP é plano; 110% ≈ 9%. */
        fun gradeFor(watts: Int?, ftp: Int): Double {
            if (watts == null || ftp <= 0) return 0.0
            return ((watts.toDouble() / ftp - 0.6) * 18).coerceIn(MIN_GRADE, MAX_GRADE)
        }

        /** Monta a estrada do treino (com o ajuste −10/+10 W) ou, sem treino, a do pedal livre. */
        fun of(plan: WorkoutPlan?, ftp: Int, offsetW: Int = 0): SceneTrack {
            if (plan == null || plan.blocks.isEmpty()) {
                return SceneTrack(IntArray(0), IntArray(0), emptyArray(), IntArray(0), DoubleArray(0), free = true)
            }
            val n = plan.blocks.size
            val starts = IntArray(n); val durs = IntArray(n)
            val watts = arrayOfNulls<Int>(n); val zones = IntArray(n); val grades = DoubleArray(n)
            var acc = 0
            plan.blocks.forEachIndexed { i, b ->
                starts[i] = acc; durs[i] = b.durationSec; acc += b.durationSec
                val w = b.target.ergWatts(ftp)?.let { (it + offsetW).coerceAtLeast(0) }
                watts[i] = w
                zones[i] = when (val t = b.target) {
                    is PowerTarget.Zone -> t.zone
                    is PowerTarget.Watts -> CogganZones.zoneFor(w ?: t.watts, ftp)
                    PowerTarget.Free -> 0
                }
                grades[i] = gradeFor(w, ftp)
            }
            return SceneTrack(starts, durs, watts, zones, grades, free = false)
        }

        private fun mix(a: Double, b: Double, u: Double) = a + (b - a) * u
        private fun smooth(u: Double): Double { val x = u.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }
    }
}
