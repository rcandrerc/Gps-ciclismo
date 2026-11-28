package br.com.gpsciclismo.core

import kotlin.math.max

/**
 * Velocidade "de estrada" calculada pela potência, para o treino indoor.
 *
 * A velocidade que o rolo manda depende da marcha e do volante (no ERG, marcha
 * pesada = 45-50 km/h com a mesma potência). Por isso o indoor usa a física de
 * uma estrada plana sem vento: P·η = (Crr·m·g + ½·ρ·CdA·v²)·v.
 */
object VirtualSpeed {
    private const val G = 9.81
    private const val RHO = 1.2        // densidade do ar (kg/m³)
    private const val CDA = 0.36       // posição nas manetes, mais conservador (m²)
    private const val CRR = 0.004      // pneu de estrada no asfalto
    private const val EFFICIENCY = 0.97 // perda na transmissão
    private const val BIKE_KG = 9.0
    private const val DEFAULT_RIDER_KG = 75.0

    /** km/h para [watts] com o ciclista pesando [riderKg]. 0 W = parado. */
    fun kmh(watts: Int, riderKg: Double): Double {
        if (watts <= 0) return 0.0
        val m = (if (riderKg in 30.0..250.0) riderKg else DEFAULT_RIDER_KG) + BIKE_KG
        val p = watts * EFFICIENCY
        val roll = CRR * m * G
        val aero = 0.5 * RHO * CDA
        // f(v) = aero·v³ + roll·v − p é crescente: bisseção simples.
        var lo = 0.0
        var hi = 30.0 // m/s (108 km/h)
        repeat(50) {
            val v = (lo + hi) / 2
            if (aero * v * v * v + roll * v > p) hi = v else lo = v
        }
        return max(0.0, (lo + hi) / 2 * 3.6)
    }
}
