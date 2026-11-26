package br.com.gpsciclismo.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** Contas do histórico de peso, sem Android (dá para testar no computador). */
object WeightLog {
    const val MIN_KG = 30.0
    const val MAX_KG = 250.0

    /** Lê o que foi digitado ("82,4" ou "82.4"). Null se não for um peso plausível. */
    fun parse(text: String): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (v < MIN_KG || v > MAX_KG) return null
        return (v * 10).roundToInt() / 10.0
    }

    /** Já se pesou hoje? (mesmo dia no fuso do aparelho). */
    fun sameDay(a: Long?, b: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (a == null) return false
        val ca = Calendar.getInstance(tz).apply { timeInMillis = a }
        val cb = Calendar.getInstance(tz).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** W/kg com uma casa, ou null sem peso/FTP. */
    fun wattsPerKg(ftp: Int?, weightKg: Double): Double? {
        if (ftp == null || ftp <= 0 || weightKg <= 0) return null
        return (ftp / weightKg * 10).roundToInt() / 10.0
    }

    /**
     * Variação até hoje em relação à pesagem mais recente feita há pelo menos [days] dias.
     * [entries] = (quando, kg). Null se não houver pesagem tão antiga.
     */
    fun change(entries: List<Pair<Long, Double>>, days: Int, now: Long): Double? {
        val latest = entries.maxByOrNull { it.first } ?: return null
        val limit = now - days * DAY_MS
        val old = entries.filter { it.first <= limit }.maxByOrNull { it.first } ?: return null
        return ((latest.second - old.second) * 10).roundToInt() / 10.0
    }

    private const val DAY_MS = 24L * 3600 * 1000
}
