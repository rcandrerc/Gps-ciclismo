package br.com.gpsciclismo.core

/**
 * Bluetooth FTMS (Fitness Machine Service, 0x1826), usado pelos rolos smart
 * (ThinkRider, Wahoo, Tacx, Elite...):
 *  - Indoor Bike Data (0x2AD2): velocidade, cadência e potência do rolo.
 *  - Control Point (0x2AD9): o app pede o controle e manda a carga (modo ERG).
 */
object Ftms {

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    data class IndoorBikeData(val speedKmh: Double?, val cadence: Int?, val power: Int?)

    /** Decodifica o Indoor Bike Data. Campos ausentes voltam null. */
    fun indoorBikeData(data: ByteArray): IndoorBikeData? {
        if (data.size < 2) return null
        val flags = u16(data, 0)
        var i = 2
        fun has(n: Int) = data.size >= i + n
        var speed: Double? = null
        var cadence: Int? = null
        var power: Int? = null
        // bit 0 = "More Data": quando ZERO, a velocidade instantânea está presente.
        if (flags and 0x0001 == 0) { if (!has(2)) return null; speed = u16(data, i) / 100.0; i += 2 }
        if (flags and 0x0002 != 0) i += 2 // velocidade média
        if (flags and 0x0004 != 0) { if (!has(2)) return null; cadence = u16(data, i) / 2; i += 2 }
        if (flags and 0x0008 != 0) i += 2 // cadência média
        if (flags and 0x0010 != 0) i += 3 // distância total
        if (flags and 0x0020 != 0) i += 2 // nível de resistência
        if (flags and 0x0040 != 0) { if (!has(2)) return null; power = s16(data, i).coerceAtLeast(0) }
        return IndoorBikeData(speed, cadence, power)
    }

    // ---- Comandos do Control Point ----
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_SIMULATION = 0x11
    const val OP_RESPONSE = 0x80
    const val RESULT_SUCCESS = 0x01

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(OP_RESET.toByte())
    fun startOrResume() = byteArrayOf(OP_START_RESUME.toByte())

    /** Modo ERG: o rolo segura esta potência, qualquer que seja a cadência. */
    fun setTargetPower(watts: Int): ByteArray {
        val w = watts.coerceIn(0, 2000)
        return byteArrayOf(OP_SET_TARGET_POWER.toByte(), (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())
    }

    /** Simulação de estrada plana (0% de inclinação), para pedalar livre sem ERG travando a carga. */
    fun flatRoad(): ByteArray = byteArrayOf(
        OP_SIMULATION.toByte(),
        0, 0, // vento 0
        0, 0, // inclinação 0,00%
        40,   // Crr 0,0040 (pneu no asfalto)
        51,   // Cw 0,51 kg/m
    )

    data class Response(val requestOp: Int, val result: Int) {
        val ok get() = result == RESULT_SUCCESS
    }

    fun response(data: ByteArray): Response? {
        if (data.size < 3 || u8(data, 0) != OP_RESPONSE) return null
        return Response(u8(data, 1), u8(data, 2))
    }
}

/** O que o app quer que o rolo faça. */
sealed class TrainerCommand {
    /** Não mexe no rolo (ERG desligado). */
    data object None : TrainerCommand()
    /** ERG: segurar estes watts. */
    data class Erg(val watts: Int) : TrainerCommand()
    /** Bloco livre com ERG ligado: estrada plana, a carga segue a marcha e a cadência. */
    data object Flat : TrainerCommand()
}
