package br.com.gpsciclismo.core

/**
 * Decodificação dos perfis Bluetooth LE padrão:
 *  - Heart Rate Measurement (0x2A37)
 *  - CSC Measurement (0x2A5B) – velocidade/cadência
 *  - Cycling Power Measurement (0x2A63)
 */
object BleParsers {

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun u32(b: ByteArray, i: Int): Long =
        (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)

    /** Retorna o batimento em bpm, ou null se o pacote for inválido. */
    fun heartRate(data: ByteArray): Int? {
        if (data.size < 2) return null
        val flags = u8(data, 0)
        return if (flags and 0x01 != 0) {
            if (data.size < 3) null else u16(data, 1)
        } else u8(data, 1)
    }

    data class CrankData(val cumulativeRevs: Int, val lastEventTime1024: Int)
    data class WheelData(val cumulativeRevs: Long, val lastEventTime1024: Int)

    data class CscMeasurement(val wheel: WheelData?, val crank: CrankData?)

    fun csc(data: ByteArray): CscMeasurement? {
        if (data.isEmpty()) return null
        val flags = u8(data, 0)
        var i = 1
        var wheel: WheelData? = null
        var crank: CrankData? = null
        if (flags and 0x01 != 0) {
            if (data.size < i + 6) return null
            wheel = WheelData(u32(data, i), u16(data, i + 4))
            i += 6
        }
        if (flags and 0x02 != 0) {
            if (data.size < i + 4) return null
            crank = CrankData(u16(data, i), u16(data, i + 2))
        }
        return CscMeasurement(wheel, crank)
    }

    data class PowerMeasurement(val watts: Int, val crank: CrankData?)

    fun cyclingPower(data: ByteArray): PowerMeasurement? {
        if (data.size < 4) return null
        val flags = u16(data, 0)
        val watts = s16(data, 2)
        var i = 4
        if (flags and 0x0001 != 0) i += 1 // pedal power balance
        if (flags and 0x0004 != 0) i += 2 // accumulated torque
        if (flags and 0x0010 != 0) i += 6 // wheel revolution data
        var crank: CrankData? = null
        if (flags and 0x0020 != 0 && data.size >= i + 4) {
            crank = CrankData(u16(data, i), u16(data, i + 2))
        }
        return PowerMeasurement(watts.coerceAtLeast(0), crank)
    }
}

/**
 * Calcula cadência (rpm) a partir de dados cumulativos de pedivela.
 * Considera estouro dos contadores de 16 bits e zera a cadência
 * quando não há pedalada nova por [stopAfterMs].
 */
class CadenceCalculator(private val stopAfterMs: Long = 3000) {
    private var last: BleParsers.CrankData? = null
    private var lastChangeAtMs: Long = 0
    private var lastRpm: Int = 0

    fun update(data: BleParsers.CrankData, nowMs: Long): Int {
        val prev = last
        last = data
        if (prev == null) {
            lastChangeAtMs = nowMs
            return 0
        }
        val revs = (data.cumulativeRevs - prev.cumulativeRevs) and 0xFFFF
        val time = (data.lastEventTime1024 - prev.lastEventTime1024) and 0xFFFF
        if (revs == 0 || time == 0) {
            if (nowMs - lastChangeAtMs > stopAfterMs) lastRpm = 0
            return lastRpm
        }
        lastChangeAtMs = nowMs
        val rpm = revs * 60.0 * 1024.0 / time
        lastRpm = if (rpm > 250) lastRpm else rpm.toInt()
        return lastRpm
    }

    fun reset() {
        last = null
        lastRpm = 0
    }
}
