package br.com.gpsciclismo.core

/**
 * Acompanha um treino estruturado segundo a segundo e decide o que falar.
 * Não depende de Android: recebe o tempo ativo do treino e a potência média
 * dos últimos segundos, e devolve frases para o sintetizador de voz.
 */
class PowerCoach(
    private val plan: WorkoutPlan,
    private val settings: Settings,
) {
    data class Settings(
        val ftp: Int,
        val tolerancePct: Int = 5,
        /** Segundos seguidos fora da faixa antes de avisar. */
        val alertDelaySec: Int = 5,
        /** Intervalo para repetir o aviso enquanto continuar fora. */
        val repeatEverySec: Int = 15,
        /** Segundos no começo de cada bloco sem avisos (tempo para ajustar). */
        val graceSec: Int = 10,
        /** Aviso antecipado da troca de bloco. */
        val warnBeforeEndSec: Int = 10,
    )

    enum class Status { NO_TARGET, IN_ZONE, BELOW, ABOVE, FINISHED }

    data class State(
        val blockIndex: Int,
        val blockRemainingSec: Int,
        val target: PowerTarget?,
        val range: IntRange?,
        val status: Status,
    )

    private var currentBlock = -1
    private var outSince: Int? = null
    private var outDirection: Status? = null
    private var lastAlertAt: Int? = null
    private var alerted = false
    private var warnedEnd = false
    private var finished = false

    var state: State = State(-1, 0, null, null, Status.NO_TARGET)
        private set

    private fun blockAt(elapsed: Int): Pair<Int, Int>? {
        var start = 0
        plan.blocks.forEachIndexed { i, b ->
            if (elapsed < start + b.durationSec) return i to (start + b.durationSec - elapsed)
            start += b.durationSec
        }
        return null
    }

    private fun describe(block: WorkoutBlock): String {
        val dur = formatDuration(block.durationSec)
        val t = block.target
        return when (t) {
            is PowerTarget.Zone -> {
                val r = t.range(settings.ftp, settings.tolerancePct)!!
                "$dur em zona ${t.zone}, entre ${r.first} e ${r.last} watts"
            }
            else -> "$dur a ${t.spoken()}"
        }
    }

    /**
     * @param elapsedSec tempo ativo de treino (sem pausas).
     * @param power potência média recente em watts, ou null se o sensor não está mandando dados.
     * @return frases a serem faladas agora (normalmente vazia).
     */
    fun tick(elapsedSec: Int, power: Int?): List<String> {
        if (finished) return emptyList()
        val out = mutableListOf<String>()
        val pos = blockAt(elapsedSec)
        if (pos == null) {
            finished = true
            state = State(plan.blocks.size, 0, null, null, Status.FINISHED)
            return listOf("Treino concluído. Mandou bem!")
        }
        val (index, remaining) = pos
        val block = plan.blocks[index]
        if (index != currentBlock) {
            currentBlock = index
            outSince = null; outDirection = null; lastAlertAt = null
            alerted = false; warnedEnd = false
            out += "Bloco ${index + 1} de ${plan.blocks.size}. ${describe(block)}."
        }
        val blockElapsed = block.durationSec - remaining

        val next = plan.blocks.getOrNull(index + 1)
        if (!warnedEnd && remaining <= settings.warnBeforeEndSec && block.durationSec > settings.warnBeforeEndSec * 2) {
            warnedEnd = true
            out += if (next != null) "Faltam ${remaining} segundos. Próximo: ${describe(next)}."
            else "Faltam ${remaining} segundos para terminar."
        }

        val range = block.target.range(settings.ftp, settings.tolerancePct)
        val status = compare(power, range)
        state = State(index, remaining, block.target, range, status)

        if (range != null && power != null && blockElapsed >= settings.graceSec && !(remaining <= settings.warnBeforeEndSec)) {
            if (status == Status.BELOW || status == Status.ABOVE) {
                if (outDirection != status) {
                    outDirection = status
                    outSince = elapsedSec
                    lastAlertAt = null
                }
                val outFor = elapsedSec - (outSince ?: elapsedSec)
                val last = lastAlertAt
                val due = if (last == null) outFor >= settings.alertDelaySec
                else elapsedSec - last >= settings.repeatEverySec
                if (due) {
                    lastAlertAt = elapsedSec
                    alerted = true
                    out += alertText(status, block.target, range)
                }
            } else {
                if (alerted) out += "Boa, na zona."
                alerted = false
                outDirection = null; outSince = null; lastAlertAt = null
            }
        }
        return out
    }

    private fun alertText(status: Status, target: PowerTarget, range: IntRange): String {
        val goal = when (target) {
            is PowerTarget.Watts -> "${target.watts}"
            else -> if (status == Status.BELOW) "${range.first}" else "${range.last}"
        }
        return if (status == Status.BELOW) "Tá abaixo. Sobe pra $goal watts."
        else "Tá muito acima. Baixa pra $goal watts."
    }

    companion object {
        /** Compara a potência com a faixa do alvo (usado nos avisos e na cor da tela). */
        fun compare(power: Int?, range: IntRange?): Status = when {
            range == null || power == null -> Status.NO_TARGET
            power < range.first -> Status.BELOW
            power > range.last -> Status.ABOVE
            else -> Status.IN_ZONE
        }

        fun formatDuration(sec: Int): String {
            val m = sec / 60
            val s = sec % 60
            return when {
                m == 0 -> "$s segundos"
                s == 0 -> if (m == 1) "1 minuto" else "$m minutos"
                else -> "$m minutos e $s segundos"
            }
        }
    }
}

/**
 * Média móvel de potência por tempo, como o "3s power" do Garmin/Wahoo.
 * Recebe uma amostra por segundo e guarda as dos últimos [windowSec] segundos
 * (com meio segundo de folga para o atraso do relógio, assim 3 s = 3 amostras).
 * Zeros entram na conta (parar de pedalar baixa a média); falha do sensor não.
 */
class RollingAverage(windowSec: Int = 3) {
    private val windowMs = windowSec.coerceAtLeast(1) * 1000L
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun add(timeMs: Long, value: Int) {
        samples.addLast(timeMs to value)
        trim(timeMs)
    }

    fun average(nowMs: Long): Int? {
        trim(nowMs)
        if (samples.isEmpty()) return null
        return Math.round(samples.sumOf { it.second }.toDouble() / samples.size).toInt()
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs - 500) samples.removeFirst()
    }

    fun clear() = samples.clear()

    companion object {
        /** Opções oferecidas nas configurações (1 = instantânea). */
        val OPTIONS = listOf(1, 3, 5, 10)
    }
}
