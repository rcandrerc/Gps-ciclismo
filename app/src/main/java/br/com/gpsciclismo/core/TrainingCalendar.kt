package br.com.gpsciclismo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Plano de treinos por data, publicado no repositório (plano/calendario.json).
 * Cada dia traz o treino no mesmo formato de [WorkoutPlan]; dia sem treino é descanso.
 */
@Serializable
data class TrainingCalendar(
    val name: String = "Plano de treinos",
    val updated: String = "",
    val days: List<CalendarDay> = emptyList(),
) {
    fun day(date: LocalDate): CalendarDay? = days.lastOrNull { it.localDate() == date }

    /** Próximo dia com treino a partir de [from] (inclusive). */
    fun next(from: LocalDate): CalendarDay? = days
        .filter { d -> d.workout != null && d.localDate()?.isBefore(from) == false }
        .minByOrNull { it.localDate()!! }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TrainingCalendar = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CalendarDay(
    /** Data no formato AAAA-MM-DD. */
    val date: String,
    val title: String = "",
    val notes: String = "",
    /** Onde fazer: "rolo", "rua" ou vazio. */
    val where: String = "",
    val workout: WorkoutPlan? = null,
) {
    fun localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
    val isRest: Boolean get() = workout == null
    fun displayTitle(): String = title.ifBlank { workout?.name ?: "Descanso" }
}
