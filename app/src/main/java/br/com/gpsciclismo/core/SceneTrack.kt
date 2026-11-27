package br.com.gpsciclismo.core

/**
 * A "estrada" da cena 2D: sempre plana e reta. O que muda com a intensidade é a postura do ciclista
 * (zona leve = tronco ereto, zona alta = posição de ataque), ver [postureFor].
 * Guarda os blocos do treino (início, duração, watts, zona) para as placas e a faixa do treino.
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
    /** FTP usado para montar a cena (para a postura). */
    val ftp: Int,
) {
    val totalSec: Int = if (starts.isEmpty()) 0 else starts.last() + durations.last()
    val blockCount: Int get() = starts.size

    fun blockAt(t: Double): Int {
        if (starts.isEmpty()) return -1
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Postura (0 = ereto, 1 = ataque) para uma potência, com o FTP desta cena. */
    fun postureAt(watts: Float): Float = postureFor(watts, ftp)

    companion object {
        /** Abaixo disso (fim da Z2) o corpo fica ereto. */
        const val UPRIGHT_RATIO = 0.65f
        /** A partir disso (Z6, anaeróbico) é ataque total. */
        const val ATTACK_RATIO = 1.25f

        /**
         * Postura pela intensidade, de 0 (ereto, Z1/Z2) a 1 (ataque, Z6+), crescendo junto com a zona:
         * Z3 ≈ 0,2 · Z4 ≈ 0,5 · Z5 ≈ 0,8.
         */
        fun postureFor(watts: Float, ftp: Int): Float {
            if (ftp <= 0 || watts <= 0f) return 0f
            val u = ((watts / ftp - UPRIGHT_RATIO) / (ATTACK_RATIO - UPRIGHT_RATIO)).coerceIn(0f, 1f)
            return u * u * (3 - 2 * u)
        }

        /** Monta os blocos do treino (com o ajuste −10/+10 W) ou, sem treino, a cena do pedal livre. */
        fun of(plan: WorkoutPlan?, ftp: Int, offsetW: Int = 0): SceneTrack {
            if (plan == null || plan.blocks.isEmpty()) {
                return SceneTrack(IntArray(0), IntArray(0), emptyArray(), IntArray(0), ftp)
            }
            val n = plan.blocks.size
            val starts = IntArray(n); val durs = IntArray(n)
            val watts = arrayOfNulls<Int>(n); val zones = IntArray(n)
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
            }
            return SceneTrack(starts, durs, watts, zones, ftp)
        }
    }
}
