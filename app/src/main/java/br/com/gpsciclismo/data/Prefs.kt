package br.com.gpsciclismo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Profile(
    val name: String = "",
    val weightKg: Double = 0.0,
    val heightCm: Int = 0,
    val ftp: Int = 200,
)

data class CoachSettings(
    val tolerancePct: Int = 5,
    val alertDelaySec: Int = 5,
    val repeatEverySec: Int = 15,
    /** Resumo falado a cada N minutos (0 = desligado). */
    val summaryEveryMin: Int = 5,
    val voiceEnabled: Boolean = true,
)

enum class SensorKind(val label: String) { HEART_RATE("Frequência cardíaca"), CADENCE("Cadência"), POWER("Potência") }

data class SavedSensor(val kind: SensorKind, val address: String, val name: String)

/** Perfil, configurações e sensores pareados, guardados só no celular. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gps_ciclismo", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _coach = MutableStateFlow(readCoach())
    val coach: StateFlow<CoachSettings> = _coach.asStateFlow()

    private val _sensors = MutableStateFlow(readSensors())
    val sensors: StateFlow<Map<SensorKind, SavedSensor>> = _sensors.asStateFlow()

    private fun readProfile() = Profile(
        name = sp.getString("name", "") ?: "",
        weightKg = sp.getFloat("weightKg", 0f).toDouble(),
        heightCm = sp.getInt("heightCm", 0),
        ftp = sp.getInt("ftp", 200),
    )

    fun saveProfile(p: Profile) {
        sp.edit().putString("name", p.name).putFloat("weightKg", p.weightKg.toFloat())
            .putInt("heightCm", p.heightCm).putInt("ftp", p.ftp).apply()
        _profile.value = p
    }

    private fun readCoach() = CoachSettings(
        tolerancePct = sp.getInt("tolerancePct", 5),
        alertDelaySec = sp.getInt("alertDelaySec", 5),
        repeatEverySec = sp.getInt("repeatEverySec", 15),
        summaryEveryMin = sp.getInt("summaryEveryMin", 5),
        voiceEnabled = sp.getBoolean("voiceEnabled", true),
    )

    fun saveCoach(c: CoachSettings) {
        sp.edit().putInt("tolerancePct", c.tolerancePct).putInt("alertDelaySec", c.alertDelaySec)
            .putInt("repeatEverySec", c.repeatEverySec).putInt("summaryEveryMin", c.summaryEveryMin)
            .putBoolean("voiceEnabled", c.voiceEnabled).apply()
        _coach.value = c
    }

    private fun readSensors(): Map<SensorKind, SavedSensor> = SensorKind.entries.mapNotNull { k ->
        val addr = sp.getString("sensor_${k.name}_addr", null) ?: return@mapNotNull null
        k to SavedSensor(k, addr, sp.getString("sensor_${k.name}_name", "") ?: "")
    }.toMap()

    fun saveSensor(s: SavedSensor) {
        sp.edit().putString("sensor_${s.kind.name}_addr", s.address)
            .putString("sensor_${s.kind.name}_name", s.name).apply()
        _sensors.value = readSensors()
    }

    fun removeSensor(kind: SensorKind) {
        sp.edit().remove("sensor_${kind.name}_addr").remove("sensor_${kind.name}_name").apply()
        _sensors.value = readSensors()
    }
}
