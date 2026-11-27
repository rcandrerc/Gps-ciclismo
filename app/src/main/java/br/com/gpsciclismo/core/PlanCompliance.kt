package br.com.gpsciclismo.core

import java.time.LocalDate

/** Como ficou um dia do plano, para marcar no calendário sem abrir o dia. */
enum class DayStatus {
    /** Treino planejado e feito (tempo quase todo e aproveitamento razoável). */
    DONE,
    /** Pedalou, mas bem menos que o planejado ou com aproveitamento baixo. */
    PARTIAL,
    /** Dia de treino que já passou sem nenhum pedal. */
    MISSED,
    /** Treino planejado para hoje ou depois, ainda não feito. */
    PLANNED,
    /** Descanso planejado (nunca conta como furo). */
    REST,
    /** Pedalou num dia sem treino planejado (descanso ou vazio). */
    EXTRA,
    /** Nada planejado e nada feito. */
    NONE,
}

/** Resumo do que o atleta fez de um dia: tempo pedalado e aproveitamento (null sem blocos). */
data class DoneRide(val sec: Int, val adherencePct: Int?)

/** Taxa de conclusão dos treinos planejados num período. */
data class ComplianceSummary(
    val planned: Int,
    val done: Int,
    val partial: Int,
    val missed: Int,
    /** 0..100: feito conta inteiro, parcial conta metade. Null se não havia treino planejado. */
    val ratePct: Int?,
) {
    /** Frase curta para a tela inicial. */
    fun verdict(): String = when {
        ratePct == null -> "Nenhum treino planejado nos últimos dias."
        ratePct >= 85 -> "Indo muito bem, quase nenhum furo."
        ratePct >= 65 -> "Indo bem, mas dá para furar menos."
        ratePct >= 40 -> "Furando bastante. Bora retomar a rotina."
        else -> "Furando muito. Que tal ajustar o plano à sua semana?"
    }
}

object PlanCompliance {
    /** Abaixo disto do tempo planejado o treino conta como parcial. */
    const val MIN_TIME_FRACTION = 0.8
    /** Abaixo disto de aproveitamento (quando há blocos) conta como parcial. */
    const val MIN_ADHERENCE = 50

    fun status(day: CalendarDay?, rides: List<DoneRide>, date: LocalDate, today: LocalDate): DayStatus {
        val plan = day?.workout
        if (plan == null) {
            return when {
                rides.isNotEmpty() -> DayStatus.EXTRA
                day != null -> DayStatus.REST
                else -> DayStatus.NONE
            }
        }
        if (rides.isEmpty()) return if (date.isBefore(today)) DayStatus.MISSED else DayStatus.PLANNED
        val sec = rides.sumOf { it.sec }
        val bestAdherence = rides.mapNotNull { it.adherencePct }.maxOrNull()
        val longEnough = plan.totalSec <= 0 || sec >= plan.totalSec * MIN_TIME_FRACTION
        val goodEnough = bestAdherence == null || bestAdherence >= MIN_ADHERENCE
        return if (longEnough && goodEnough) DayStatus.DONE else DayStatus.PARTIAL
    }

    /**
     * Taxa de conclusão dos [days] dias até [today]. Hoje só conta se já foi feito
     * (o dia ainda não acabou, então não é furo).
     */
    fun summary(
        calendar: TrainingCalendar?,
        ridesByDay: Map<LocalDate, List<DoneRide>>,
        today: LocalDate,
        days: Int = 30,
    ): ComplianceSummary {
        var planned = 0; var done = 0; var partial = 0; var missed = 0
        if (calendar != null) {
            for (i in 0 until days) {
                val date = today.minusDays(i.toLong())
                when (status(calendar.day(date), ridesByDay[date].orEmpty(), date, today)) {
                    DayStatus.DONE -> { planned++; done++ }
                    DayStatus.PARTIAL -> { planned++; partial++ }
                    DayStatus.MISSED -> { planned++; missed++ }
                    else -> {}
                }
            }
        }
        val rate = if (planned == 0) null else ((done + partial * 0.5) * 100 / planned).toInt()
        return ComplianceSummary(planned, done, partial, missed, rate)
    }
}
