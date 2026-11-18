package br.com.gpsciclismo.core

import kotlin.random.Random

/**
 * Acompanha um treino estruturado segundo a segundo e decide o que falar,
 * como um treinador: anuncia cada bloco (zona, tempo, cadência), avisa antes
 * da troca, incentiva no fim dos blocos fortes e corrige quando sai da zona.
 * Não depende de Android: recebe o tempo ativo do treino e a potência média
 * dos últimos segundos, e devolve frases para o sintetizador de voz.
 */
class PowerCoach(
    private val plan: WorkoutPlan,
    private val settings: Settings,
    private val random: Random = Random.Default,
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
        /** Aviso antecipado da troca de bloco (tempo para a frase terminar antes da troca). */
        val warnBeforeEndSec: Int = 10,
        /** Nome do ciclista nas frases (vazio = sem nome). */
        val name: String = "",
        /** Cadência pedida quando o bloco não diz outra. */
        val defaultCadence: Int = 90,
    )

    enum class Status { NO_TARGET, IN_ZONE, BELOW, ABOVE, FINISHED }

    data class State(
        val blockIndex: Int,
        val blockRemainingSec: Int,
        val target: PowerTarget?,
        val range: IntRange?,
        val status: Status,
        /** Bloco do esforço do teste de FTP (acima do alvo é bom, não avisa). */
        val ftpTest: Boolean = false,
        /** false = rolo solto neste bloco, mesmo com ERG ligado. */
        val erg: Boolean = true,
        /** Ajuste de intensidade do ciclista (botões −10/+10 W), já somado ao alvo. */
        val offsetW: Int = 0,
        /** Watts para o ERG neste bloco (com o ajuste), ou null em bloco livre. */
        val ergWatts: Int? = null,
    )

    private var currentBlock = -1
    private var outSince: Int? = null
    private var outDirection: Status? = null
    private var lastAlertAt: Int? = null
    private var alerted = false
    private var warnedEnd = false
    private var saidHalf = false
    private var cadLowSince: Int? = null
    private var cadAlertAt: Int? = null
    private var finished = false
    private val lastPick = HashMap<String, Int>()

    /** Aviso de potência dado neste segundo (para o bipe): BELOW, ABOVE ou null. */
    var alertNow: Status? = null
        private set

    /** Ajuste de intensidade em watts (botões −10/+10 W). Vale para o bloco atual e os seguintes. */
    var offsetW = 0
        private set

    fun adjust(deltaW: Int) {
        offsetW = (offsetW + deltaW).coerceIn(-200, 200)
        // reinicia a contagem dos avisos: o alvo mudou
        outSince = null; outDirection = null; lastAlertAt = null
    }

    /** Alvo com o ajuste aplicado (watts fixos andam junto; zona continua zona). */
    private fun shifted(t: PowerTarget): PowerTarget =
        if (t is PowerTarget.Watts && offsetW != 0) PowerTarget.Watts((t.watts + offsetW).coerceAtLeast(0)) else t

    private fun shiftedBlock(b: WorkoutBlock) = b.copy(target = shifted(b.target))

    private fun rangeOf(b: WorkoutBlock): IntRange? {
        val r = blockRange(shiftedBlock(b), settings.ftp, settings.tolerancePct) ?: return null
        if (b.target !is PowerTarget.Zone || offsetW == 0) return r
        val last = if (r.last >= NO_CEILING) r.last else r.last + offsetW
        return (r.first + offsetW).coerceAtLeast(0)..last
    }

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

    // ---------- frases ----------

    /** Troca {N} por "André, " e {n} por ", André" (ou nada, sem nome) e sorteia sem repetir a última. */
    private fun pick(key: String, options: List<String>): String {
        var i = random.nextInt(options.size)
        if (options.size > 1 && lastPick[key] == i) i = (i + 1) % options.size
        lastPick[key] = i
        return fill(options[i])
    }

    private fun fill(s: String): String {
        val first = settings.name.trim().substringBefore(' ')
        return s.replace("{N}", if (first.isEmpty()) "" else "$first, ")
            .replace("{n}", if (first.isEmpty()) "" else ", $first")
            .replaceFirstChar { it.uppercaseChar() }
    }

    /** Intensidade do alvo em fração do FTP (null = pedal livre). */
    private fun intensity(b: WorkoutBlock): Double? {
        if (settings.ftp <= 0) return null
        return shifted(b.target).ergWatts(settings.ftp)?.let { it.toDouble() / settings.ftp }
    }

    /** Bloco forte: limiar (Z4) para cima, ou o esforço do teste de FTP. */
    private fun isHard(b: WorkoutBlock): Boolean = b.ftpTest || (intensity(b) ?: 0.0) >= 0.90

    private fun zoneOf(b: WorkoutBlock): Int? = when (val t = shifted(b.target)) {
        is PowerTarget.Zone -> t.zone
        is PowerTarget.Watts -> CogganZones.zoneFor(t.watts, settings.ftp).takeIf { it > 0 }
        else -> null
    }

    private fun describe(original: WorkoutBlock): String {
        val block = shiftedBlock(original)
        val dur = formatDuration(block.durationSec)
        return when (val t = block.target) {
            is PowerTarget.Watts -> if (block.ftpTest) "$dur a ${t.watts} watts ou mais" else "$dur a ${t.spoken()}"
            is PowerTarget.Zone -> {
                val r = rangeOf(original)!!
                "$dur em zona ${t.zone}, entre ${r.first} e ${r.last} watts"
            }
            else -> "$dur de pedal livre"
        }
    }

    /** "Zona 2, 10 minutos, entre 130 e 160 watts." ou "5 minutos a 200 watts." */
    private fun blockIntro(original: WorkoutBlock): String {
        val block = shiftedBlock(original)
        val dur = formatDuration(block.durationSec)
        return when (val t = block.target) {
            is PowerTarget.Zone -> {
                val r = rangeOf(original)!!
                "Zona ${t.zone}, $dur, entre ${r.first} e ${r.last} watts."
            }
            else -> describe(original).replaceFirstChar { it.uppercaseChar() } + "."
        }
    }

    private fun cadenceOf(b: WorkoutBlock) = b.cadence ?: settings.defaultCadence

    /**
     * @param elapsedSec tempo ativo de treino (sem pausas).
     * @param power potência média recente em watts, ou null se o sensor não está mandando dados.
     * @param cadence cadência atual (rpm), ou null sem sensor.
     * @return frases a serem faladas agora (normalmente vazia).
     */
    fun tick(elapsedSec: Int, power: Int?, cadence: Int? = null): List<String> {
        alertNow = null
        if (finished) return emptyList()
        val out = mutableListOf<String>()
        val pos = blockAt(elapsedSec)
        if (pos == null) {
            finished = true
            state = State(plan.blocks.size, 0, null, null, Status.FINISHED)
            return listOf(pick("fim", listOf(
                "Treino concluído{n}! Mandou bem.",
                "Acabou{n}! Treino concluído, parabéns.",
                "Treino concluído. Missão cumprida{n}.",
            )))
        }
        val (index, remaining) = pos
        val block = plan.blocks[index]
        val next = plan.blocks.getOrNull(index + 1)
        val prev = plan.blocks.getOrNull(index - 1)
        if (index != currentBlock) {
            currentBlock = index
            outSince = null; outDirection = null; lastAlertAt = null
            alerted = false; warnedEnd = false; saidHalf = false
            cadLowSince = null; cadAlertAt = null
            val last = next == null && plan.blocks.size > 1 && !block.ftpTest
            val cooldown = last && prev != null && (intensity(block) ?: 0.0) < 0.76 &&
                (intensity(block) ?: 0.0) < (intensity(prev) ?: 0.0)
            if (cooldown) out += pick("ultimo", listOf(
                "Último bloco{n}. Agora é soltar as pernas.",
                "Treino forte feito. Agora recupera com calma{n}.",
                "Último bloco. Respira e solta as pernas{n}.",
            )) else if (last) out += pick("ultimoForte", listOf(
                "Último bloco{n}. Vamos fechar bem.",
                "Último bloco. É agora{n}.",
            ))
            out += blockIntro(block)
            block.note?.takeIf { it.isNotBlank() }?.let { out += it }
            if (block.target !is PowerTarget.Free) {
                val c = cadenceOf(block)
                out += pick("cad", listOf(
                    "{N}mantenha a cadência de $c.",
                    "Cadência de $c{n}.",
                    "{N}gira em $c.",
                ))
            }
        }
        val blockElapsed = block.durationSec - remaining
        val hard = isHard(block)

        // Metade de um intervalo forte
        if (hard && !saidHalf && block.durationSec >= 60 && blockElapsed >= block.durationSec / 2 &&
            remaining > settings.warnBeforeEndSec + 5) {
            saidHalf = true
            out += pick("metade", listOf(
                "Metade feita{n}. Mantém o ritmo.",
                "Já foi metade. Continua assim.",
                "Bom trabalho até aqui{n}, segue firme.",
            ))
        }

        // Aviso antes da troca de bloco (cedo o bastante para a frase terminar antes)
        val warnAt = if (hard) settings.warnBeforeEndSec + 2 else settings.warnBeforeEndSec
        if (!warnedEnd && remaining <= warnAt && block.durationSec > settings.warnBeforeEndSec * 2) {
            warnedEnd = true
            out += endWarning(block, next, remaining)
        }

        val range = rangeOf(block)
        val status = compare(power, range)
        val target = shifted(block.target)
        val erg = target.ergWatts(settings.ftp)?.let { if (block.target is PowerTarget.Zone) it + offsetW else it }?.coerceAtLeast(0)
        state = State(index, remaining, target, range, status, block.ftpTest, block.erg, offsetW, erg)

        val quietEnd = remaining <= warnAt
        if (range != null && power != null && blockElapsed >= settings.graceSec && !quietEnd) {
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
                    alertNow = status
                    out += alertText(status, target, range)
                }
            } else {
                if (alerted) out += pick("volta", listOf("Boa, na zona.", "Isso{n}, voltou pra zona.", "Boa{n}, agora sim."))
                alerted = false
                outDirection = null; outSince = null; lastAlertAt = null
            }
        }

        // Cadência baixa (só com sensor e pedalando)
        if (cadence != null && cadence >= 30 && block.target !is PowerTarget.Free &&
            blockElapsed >= settings.graceSec && !quietEnd && alertNow == null) {
            val goal = cadenceOf(block)
            if (cadence < goal - 10) {
                val since = cadLowSince ?: elapsedSec.also { cadLowSince = it }
                val lastCad = cadAlertAt
                if (elapsedSec - since >= 15 && (lastCad == null || elapsedSec - lastCad >= 60)) {
                    cadAlertAt = elapsedSec
                    out += pick("cadBaixa", listOf(
                        "{N}gira mais rápido, cadência baixa.",
                        "Aumenta a cadência{n}. Alvo $goal.",
                        "Cadência caiu{n}. Volta pra $goal.",
                    ))
                }
            } else cadLowSince = null
        }
        return out
    }

    private fun endWarning(block: WorkoutBlock, next: WorkoutBlock?, remaining: Int): String {
        val cur = intensity(block) ?: 0.0
        val hard = isHard(block)
        if (next == null) {
            return if (hard) pick("final", FINAL_PUSH)
            else pick("acabando", listOf("Faltam $remaining segundos para terminar{n}.", "Reta final{n}, faltam $remaining segundos."))
        }
        val nxt = intensity(next) ?: 0.0
        return when {
            // fim de bloco forte indo para descanso: incentivo para terminar
            hard && nxt < cur - 0.05 -> pick("final", FINAL_PUSH)
            nxt > cur + 0.05 && isHard(next) -> {
                val z = zoneOf(next)
                pick("subirForte", listOf(
                    "{N}agora vai começar um intervalo mais forte. Concentra.",
                    if (z != null) "Prepara{n}. Vem aí zona $z. Respira e foca." else "Prepara{n}. Vem aí um bloco forte. Respira e foca.",
                    "Atenção{n}, o próximo bloco sobe. Firme na cadência.",
                ))
            }
            nxt > cur + 0.05 -> pick("subir", listOf(
                "{N}o próximo bloco sobe um pouco. Prepara.",
                "Atenção{n}, vai subir a intensidade.",
            ))
            nxt < cur - 0.05 -> pick("descer", listOf(
                "Aguenta só mais um pouco{n}, o descanso já vem.",
                "Quase lá. Daqui a pouco é recuperação.",
                "Segura firme{n}, logo vem o alívio.",
            ))
            else -> "Faltam $remaining segundos. Próximo: ${describe(next)}."
        }
    }

    private fun alertText(status: Status, target: PowerTarget, range: IntRange): String {
        val goal = when (target) {
            is PowerTarget.Watts -> "${target.watts}"
            else -> if (status == Status.BELOW) "${range.first}" else "${range.last}"
        }
        return if (status == Status.BELOW) pick("abaixo", listOf(
            "Tá abaixo{n}. Sobe pra $goal watts.",
            "Pode apertar mais{n}. Sobe pra $goal watts.",
            "Um pouco mais forte. Sobe pra $goal watts.",
        )) else pick("acima", listOf(
            "Tá muito acima{n}. Baixa pra $goal watts.",
            "Calma{n}, segura um pouco. Baixa pra $goal watts.",
            "Tá forte demais, alivia. Baixa pra $goal watts.",
        ))
    }

    companion object {
        private val FINAL_PUSH = listOf(
            "{N}não desista. Foco, falta muito pouco.",
            "Últimos segundos{n}. Termina forte.",
            "Tá acabando{n}. Segura até o fim.",
            "Você tá quase lá{n}. Não tira o pé.",
        )

        /** No teste de FTP o alvo é um piso: qualquer coisa acima dele está bom. */
        fun blockRange(block: WorkoutBlock, ftp: Int, tolerancePct: Int): IntRange? {
            val r = block.target.range(ftp, tolerancePct) ?: return null
            return if (block.ftpTest) r.first..NO_CEILING else r
        }

        const val NO_CEILING = 9999

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
