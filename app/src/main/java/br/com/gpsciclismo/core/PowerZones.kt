package br.com.gpsciclismo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** As 7 zonas de potência do Coggan, em % do FTP. */
object CogganZones {
    data class Zone(val number: Int, val name: String, val minPct: Double, val maxPct: Double)

    val zones = listOf(
        Zone(1, "Recuperação ativa", 0.0, 0.55),
        Zone(2, "Endurance", 0.55, 0.75),
        Zone(3, "Tempo", 0.75, 0.90),
        Zone(4, "Limiar", 0.90, 1.05),
        Zone(5, "VO2 máx", 1.05, 1.20),
        Zone(6, "Capacidade anaeróbica", 1.20, 1.50),
        Zone(7, "Neuromuscular", 1.50, 3.00),
    )

    fun zone(number: Int): Zone = zones[(number - 1).coerceIn(0, zones.lastIndex)]

    fun range(number: Int, ftp: Int): IntRange {
        val z = zone(number)
        return (z.minPct * ftp).toInt()..(z.maxPct * ftp).toInt()
    }

    fun zoneFor(watts: Int, ftp: Int): Int {
        if (ftp <= 0) return 0
        val pct = watts.toDouble() / ftp
        return zones.firstOrNull { pct <= it.maxPct }?.number ?: 7
    }
}

@Serializable
sealed class PowerTarget {
    /** Alvo fixo em watts, com tolerância em % para cima e para baixo. */
    @Serializable
    @SerialName("watts")
    data class Watts(val watts: Int) : PowerTarget()

    /** Alvo em zona do Coggan (1 a 7). */
    @Serializable
    @SerialName("zone")
    data class Zone(val zone: Int) : PowerTarget()

    /** Sem alvo (pedal livre). */
    @Serializable
    @SerialName("free")
    data object Free : PowerTarget()

    fun range(ftp: Int, tolerancePct: Int): IntRange? = when (this) {
        is Watts -> {
            val tol = (watts * tolerancePct / 100.0).toInt().coerceAtLeast(5)
            (watts - tol)..(watts + tol)
        }
        is Zone -> CogganZones.range(zone, ftp)
        Free -> null
    }

    fun spoken(): String = when (this) {
        is Watts -> "$watts watts"
        is Zone -> "zona $zone"
        Free -> "pedal livre"
    }

    fun label(): String = when (this) {
        is Watts -> "$watts W"
        is Zone -> "Z$zone"
        Free -> "Livre"
    }
}

@Serializable
data class WorkoutBlock(val durationSec: Int, val target: PowerTarget)

@Serializable
data class WorkoutPlan(val name: String, val blocks: List<WorkoutBlock>) {
    val totalSec: Int get() = blocks.sumOf { it.durationSec }
}
