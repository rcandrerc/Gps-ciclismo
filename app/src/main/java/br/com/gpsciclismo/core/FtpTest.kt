package br.com.gpsciclismo.core

/**
 * Soma a potência dos segundos do esforço do teste de FTP (blocos com `ftpTest`).
 * FTP estimado = 95% da média (protocolo de 20 min). Zeros contam (parar de pedalar
 * baixa a média); segundo sem sinal do medidor não entra.
 */
class FtpTestTracker {
    var sec = 0; private set
    var noSignalSec = 0; private set
    private var sum = 0L

    fun add(power: Int?) {
        if (power == null) { noSignalSec++; return }
        sec++
        sum += power
    }

    val avg: Int? get() = if (sec == 0) null else Math.round(sum.toDouble() / sec).toInt()
    val ftp: Int? get() = if (sec < MIN_SEC) null else avg?.let(::ftpFrom)

    companion object {
        /** Menos que isso não dá para chamar de teste. */
        const val MIN_SEC = 60
        fun ftpFrom(avg20: Int): Int = Math.round(avg20 * 0.95).toInt()
        /** Teste completo: pelo menos 90% do tempo planejado com potência. */
        fun complete(sec: Int, plannedSec: Int) = plannedSec > 0 && sec >= plannedSec * 0.9
    }
}
