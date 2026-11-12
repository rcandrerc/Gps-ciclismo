package br.com.gpsciclismo.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Acumula distância, velocidade média/máxima e médias dos sensores. */
class RideStats {
    var distanceM = 0.0; private set
    var movingSec = 0; private set
    var maxSpeedKmh = 0.0; private set
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private var powerSum = 0L; private var powerN = 0
    private var hrSum = 0L; private var hrN = 0
    private var cadSum = 0L; private var cadN = 0
    var maxPower = 0; private set
    var maxHr = 0; private set
    /** Energia em joules (soma da potência a cada segundo). */
    var workJ = 0L; private set

    val avgSpeedKmh: Double get() = if (movingSec == 0) 0.0 else distanceM / movingSec * 3.6
    val avgPower: Int get() = if (powerN == 0) 0 else (powerSum / powerN).toInt()
    val avgHr: Int get() = if (hrN == 0) 0 else (hrSum / hrN).toInt()
    val avgCadence: Int get() = if (cadN == 0) 0 else (cadSum / cadN).toInt()

    /** Chamar uma vez por segundo de treino ativo. */
    fun tickSecond(speedKmh: Double?, power: Int?, hr: Int?, cadence: Int?) {
        if (speedKmh != null && speedKmh >= MOVING_KMH) movingSec++
        if (power != null) {
            powerSum += power; powerN++; workJ += power
            if (power > maxPower) maxPower = power
        }
        if (hr != null && hr > 0) {
            hrSum += hr; hrN++
            if (hr > maxHr) maxHr = hr
        }
        if (cadence != null && cadence > 0) { cadSum += cadence; cadN++ }
    }

    /** Novo ponto de GPS. Pontos imprecisos devem ser filtrados antes. */
    fun addLocation(lat: Double, lon: Double, speedKmh: Double) {
        val pLat = lastLat; val pLon = lastLon
        if (pLat != null && pLon != null) {
            val d = haversineM(pLat, pLon, lat, lon)
            // Parado, o GPS "passeia" alguns metros; só soma distância em movimento
            // (mesmo critério do tempo em movimento, senão a média fica inflada).
            if (d < 100 && speedKmh >= MOVING_KMH) distanceM += d
        }
        lastLat = lat; lastLon = lon
        if (speedKmh in 0.0..120.0 && speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
    }

    /** Depois de uma pausa, não somar o trecho entre o último ponto e o próximo. */
    fun breakSegment() { lastLat = null; lastLon = null }

    companion object {
        const val MOVING_KMH = 2.0

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Calorias aproximadas: kJ mecânicos ≈ kcal (eficiência ~24%). */
        fun kcalFromWork(workJ: Long): Int = (workJ / 1000).toInt()
    }
}
