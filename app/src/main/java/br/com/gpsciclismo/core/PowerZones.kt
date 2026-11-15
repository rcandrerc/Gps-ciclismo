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

    /**
     * Watts que o rolo segura no modo ERG. Zona vira um ponto dentro dela
     * (perto do meio; Z1 e Z7 têm faixas largas demais para usar o meio).
     */
    fun ergWatts(ftp: Int): Int? = when (this) {
        is Watts -> watts
        is Zone -> (ftp * ERG_ZONE_PCT[(zone - 1).coerceIn(0, 6)]).toInt()
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

/** % do FTP usado no ERG para cada zona (Z1 a Z7). */
private val ERG_ZONE_PCT = doubleArrayOf(0.50, 0.65, 0.83, 0.98, 1.13, 1.35, 1.50)

@Serializable
data class WorkoutBlock(
    val durationSec: Int,
    val target: PowerTarget,
    /** false = o rolo fica solto (estrada plana) neste bloco mesmo com ERG ligado; o alvo vira só referência. */
    val erg: Boolean = true,
    /** Bloco do esforço do teste de FTP: a média destes blocos dá o FTP (95%). Acima do alvo não é avisado. */
    val ftpTest: Boolean = false,
    /** Frase falada no começo do bloco (dica do treino). */
    val note: String? = null,
)

@Serializable
data class WorkoutPlan(val name: String, val blocks: List<WorkoutBlock>) {
    val totalSec: Int get() = blocks.sumOf { it.durationSec }
    /** Duração planejada do esforço do teste de FTP (0 se não é teste). */
    val ftpTestSec: Int get() = blocks.filter { it.ftpTest }.sumOf { it.durationSec }
}
