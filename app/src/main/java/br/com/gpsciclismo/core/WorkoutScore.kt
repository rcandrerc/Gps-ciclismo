package br.com.gpsciclismo.core

/**
 * Nota do treino estruturado: % do tempo com alvo em que a potência (média dos avisos)
 * ficou dentro da faixa. Não conta pausas, blocos livres nem os primeiros segundos
 * de cada bloco (tempo para ajustar a carga).
 */
class WorkoutScore {
    var targetSec = 0; private set
    var inZoneSec = 0; private set

    fun add(inZone: Boolean) {
        targetSec++
        if (inZone) inZoneSec++
    }

    /** null se não houve tempo com alvo suficiente para dar nota. */
    val pct: Int? get() = if (targetSec < MIN_SEC) null else (inZoneSec * 100.0 / targetSec).toInt()

    companion object {
        const val MIN_SEC = 30

        fun stars(pct: Int): Int = when {
            pct >= 90 -> 5
            pct >= 80 -> 4
            pct >= 65 -> 3
            pct >= 50 -> 2
            else -> 1
        }

        fun starsText(pct: Int): String = "★".repeat(stars(pct)) + "☆".repeat(5 - stars(pct))
    }
}
