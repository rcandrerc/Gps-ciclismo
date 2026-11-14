package br.com.gpsciclismo.recording

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import br.com.gpsciclismo.ble.BleSensorManager
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.RideStats
import br.com.gpsciclismo.core.RollingAverage
import br.com.gpsciclismo.core.WorkoutPlan
import br.com.gpsciclismo.data.AppDatabase
import br.com.gpsciclismo.data.Prefs
import br.com.gpsciclismo.data.RideSetup
import br.com.gpsciclismo.core.TrainerCommand
import br.com.gpsciclismo.data.RideEntity
import br.com.gpsciclismo.data.RidePointEntity
import br.com.gpsciclismo.core.VoiceQuery
import br.com.gpsciclismo.voice.VoiceAnnouncer
import br.com.gpsciclismo.voice.VoiceCommander
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class LiveRide(
    val status: RecStatus = RecStatus.IDLE,
    val rideId: Long = 0,
    val elapsedSec: Int = 0,
    val speedKmh: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val distanceM: Double = 0.0,
    /** Potência média dos últimos [powerWindowSec] segundos (valor principal da tela). */
    val powerAvg: Int? = null,
    /** Última leitura do medidor, sem média. */
    val powerNow: Int? = null,
    val powerWindowSec: Int = 3,
    val avgPower: Int = 0,
    val maxPower: Int = 0,
    val avgHr: Int = 0,
    val kcal: Int = 0,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    val gpsAccuracyM: Float? = null,
    val workout: WorkoutPlan? = null,
    val coach: PowerCoach.State? = null,
    val setup: RideSetup = RideSetup(),
    /** Carga que o app mandou o rolo segurar (ERG), ou null. */
    val ergWatts: Int? = null,
)

/** Grava o treino: GPS + sensores a cada segundo, treinador por voz e banco de dados. */
class RideRecorder(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val sensors: BleSensorManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private val _live = MutableStateFlow(LiveRide())
    val live: StateFlow<LiveRide> = _live.asStateFlow()

    private var voice: VoiceAnnouncer? = null
    private var commander: VoiceCommander? = null
    /** Avisos automáticos (o que o ciclista pergunta é sempre respondido). */
    private var autoVoice = true
    private var blockIndex = -1
    private var blockPowerSum = 0L
    private var blockPowerN = 0
    private var stats = RideStats()
    private var coach: PowerCoach? = null
    private var powerDisplay = RollingAverage(3)
    private var powerAlert = RollingAverage(5)
    private var powerDisplaySec = 3
    private var ticker: Job? = null
    private var lastLocation: Location? = null
    private var startedAt = 0L
    private val buffer = mutableListOf<RidePointEntity>()
    private var lastPausedAt: Int? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    /** Chamado pela tela: inicia o serviço em primeiro plano, que chama [begin]. */
    fun start(plan: WorkoutPlan?, setup: RideSetup = RideSetup()) {
        if (_live.value.status != RecStatus.IDLE) return
        pendingPlan = plan
        pendingSetup = setup
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
    }

    private var pendingPlan: WorkoutPlan? = null
    private var pendingSetup = RideSetup()
    private var setup = RideSetup()

    @SuppressLint("MissingPermission")
    internal fun begin() {
        if (_live.value.status != RecStatus.IDLE) return
        val plan = pendingPlan
        setup = pendingSetup
        stats = RideStats()
        buffer.clear()
        lastLocation = null
        startedAt = System.currentTimeMillis()
        val coachSettings = prefs.coach.value
        powerDisplaySec = coachSettings.powerDisplaySec
        powerDisplay = RollingAverage(coachSettings.powerDisplaySec)
        powerAlert = RollingAverage(coachSettings.powerAlertSec)
        autoVoice = coachSettings.voiceEnabled
        blockIndex = -1; blockPowerSum = 0; blockPowerN = 0
        val v = VoiceAnnouncer(context, coachSettings.voiceName, coachSettings.speechRate, useGoogle = coachSettings.googleVoice)
        voice = v
        commander = runCatching {
            VoiceCommander(context, v, onSummary = { speakSummary() }, onQuery = { answer(it) })
        }.getOrNull()
        coach = plan?.let {
            PowerCoach(it, PowerCoach.Settings(
                ftp = prefs.profile.value.ftp,
                tolerancePct = coachSettings.tolerancePct,
                alertDelaySec = coachSettings.alertDelaySec,
                repeatEverySec = coachSettings.repeatEverySec,
                // a média dos avisos ainda carrega o bloco anterior por alguns segundos
                graceSec = maxOf(10, coachSettings.powerAlertSec + 2),
            ))
        }
        sensors.connectSaved()
        if (!setup.indoor) runCatching {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(1000)
                .build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }
        _live.value = LiveRide(status = RecStatus.RECORDING, workout = plan, powerWindowSec = powerDisplaySec, setup = setup)
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                db.rides().insertRide(RideEntity(uuid = UUID.randomUUID().toString(), startedAt = startedAt, workoutName = plan?.name))
            }
            _live.value = _live.value.copy(rideId = id)
        }
        if (plan == null && autoVoice) voice?.speak("Treino iniciado. Um toque no botão do fone dá o resumo, dois toques para perguntar.")
        ticker = scope.launch {
            var next = System.currentTimeMillis()
            while (isActive) {
                next += 1000
                delay((next - System.currentTimeMillis()).coerceAtLeast(0))
                tick()
            }
        }
    }

    fun pause() {
        if (_live.value.status != RecStatus.RECORDING) return
        stats.breakSegment()
        // ERG: solta a carga na pausa (estrada plana); o bloco volta sozinho no próximo segundo após continuar.
        if (setup.ergActive && coach != null) sensors.setTrainerCommand(TrainerCommand.Flat)
        _live.value = _live.value.copy(status = RecStatus.PAUSED, ergWatts = null)
        voice?.speak("Treino pausado.", urgent = true)
    }

    fun resume() {
        if (_live.value.status != RecStatus.PAUSED) return
        _live.value = _live.value.copy(status = RecStatus.RECORDING)
        voice?.speak("Continuando.", urgent = true)
    }

    /** Finaliza e salva. Retorna o id do treino salvo. */
    suspend fun stop(): Long? {
        val cur = _live.value
        if (cur.status == RecStatus.IDLE) return null
        ticker?.cancel()
        fused.removeLocationUpdates(locationCallback)
        if (setup.ergActive) {
            sensors.setTrainerCommand(TrainerCommand.None) // solta o rolo antes de desconectar
            delay(800)
        }
        sensors.disconnectAll()
        val rideId = cur.rideId
        val endedAt = System.currentTimeMillis()
        val weight = prefs.profile.value.weightKg
        val kcal = if (stats.workJ > 0) RideStats.kcalFromWork(stats.workJ)
        else (weight * 8.0 * cur.elapsedSec / 3600.0).toInt() // estimativa sem potência (MET ~8)
        withContext(Dispatchers.IO) {
            flush()
            db.rides().ride(rideId)?.let {
                db.rides().updateRide(it.copy(
                    endedAt = endedAt,
                    distanceM = stats.distanceM,
                    movingSec = stats.movingSec,
                    elapsedSec = cur.elapsedSec,
                    avgSpeedKmh = stats.avgSpeedKmh,
                    maxSpeedKmh = stats.maxSpeedKmh,
                    avgPower = stats.avgPower,
                    maxPower = stats.maxPower,
                    avgHr = stats.avgHr,
                    maxHr = stats.maxHr,
                    avgCadence = stats.avgCadence,
                    kcal = kcal,
                ))
            }
        }
        commander?.release()
        commander = null
        voice?.speak("Treino salvo. ${summaryText()}")
        val v = voice
        scope.launch { delay(15_000); v?.shutdown() }
        voice = null
        coach = null
        _live.value = LiveRide()
        context.stopService(Intent(context, RideService::class.java))
        return rideId
    }

    private fun onLocation(loc: Location) {
        if (_live.value.status != RecStatus.RECORDING) { lastLocation = loc; return }
        if (loc.hasAccuracy() && loc.accuracy > 25f) {
            _live.value = _live.value.copy(gpsAccuracyM = loc.accuracy)
            return
        }
        lastLocation = loc
        val speed = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        stats.addLocation(loc.latitude, loc.longitude, speed)
        _live.value = _live.value.copy(gpsAccuracyM = loc.accuracy)
    }

    private fun currentSpeed(now: Long): Double? {
        if (setup.indoor) return sensors.readings.value.trainerSpeedNow(now)
        val l = lastLocation ?: return null
        if (now - l.time > 4000 || !l.hasSpeed()) return 0.0
        return l.speed * 3.6
    }

    private fun tick() {
        val cur = _live.value
        val now = System.currentTimeMillis()
        val r = sensors.readings.value
        val pw = r.powerNow(now)
        if (pw != null) { powerDisplay.add(now, pw); powerAlert.add(now, pw) }
        val avgShown = powerDisplay.average(now)
        val avgAlert = powerAlert.average(now)
        if (cur.status != RecStatus.RECORDING) {
            _live.value = cur.copy(powerAvg = avgShown, powerNow = pw, heartRate = r.heartRateNow(now), cadence = r.cadenceNow(now))
            return
        }
        val elapsed = cur.elapsedSec + 1
        val speed = currentSpeed(now)
        val hr = r.heartRateNow(now)
        val cad = r.cadenceNow(now)
        stats.tickSecond(speed, pw, hr, cad)
        if (setup.indoor) stats.addIndoorSecond(speed)

        coach?.let { c ->
            val phrases = c.tick(elapsed - 1, avgAlert)
            phrases.forEach { p ->
                when {
                    p.startsWith("Tá abaixo") -> voice?.beep(high = true)
                    p.startsWith("Tá muito acima") -> voice?.beep(high = false)
                }
                if (autoVoice) voice?.speak(p)
            }
        }
        coach?.state?.let { st ->
            if (st.blockIndex != blockIndex) { blockIndex = st.blockIndex; blockPowerSum = 0; blockPowerN = 0 }
            if (pw != null) { blockPowerSum += pw; blockPowerN++ }
        }
        val erg = updateTrainer()
        val summaryMin = prefs.coach.value.summaryEveryMin
        if (autoVoice && summaryMin > 0 && elapsed % (summaryMin * 60) == 0) voice?.speak(fullSummary())

        val loc = lastLocation?.takeIf { now - it.time < 4000 }
        if (cur.rideId != 0L) {
            val point = RidePointEntity(
                rideId = cur.rideId, time = now,
                lat = loc?.latitude, lon = loc?.longitude, altitude = loc?.takeIf { it.hasAltitude() }?.altitude,
                speedKmh = speed, power = pw, heartRate = hr, cadence = cad,
            )
            val size = synchronized(buffer) { buffer += point; buffer.size }
            if (size >= 10) scope.launch(Dispatchers.IO) { flush() }
        }

        _live.value = cur.copy(
            elapsedSec = elapsed,
            speedKmh = speed ?: 0.0,
            avgSpeedKmh = stats.avgSpeedKmh,
            maxSpeedKmh = stats.maxSpeedKmh,
            distanceM = stats.distanceM,
            powerAvg = avgShown,
            powerNow = pw,
            avgPower = stats.avgPower,
            maxPower = stats.maxPower,
            avgHr = stats.avgHr,
            kcal = RideStats.kcalFromWork(stats.workJ),
            heartRate = hr,
            cadence = cad,
            coach = coach?.state,
            ergWatts = erg,
        )
    }

    /** ERG: manda para o rolo a carga do bloco atual. Retorna os watts mandados. */
    private fun updateTrainer(): Int? {
        if (!setup.ergActive) return null
        val st = coach?.state ?: return null // pedal livre: o app não mexe no rolo
        val target = st.target.takeIf { st.status != PowerCoach.Status.FINISHED }
        val watts = target?.ergWatts(prefs.profile.value.ftp)
        sensors.setTrainerCommand(if (watts != null) TrainerCommand.Erg(watts) else TrainerCommand.Flat)
        return watts
    }

    private suspend fun flush() {
        val copy = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (copy.isNotEmpty()) db.rides().insertPoints(copy)
    }

    /** Para o botão de microfone na tela. */
    fun askByVoice() {
        runCatching { commander?.listen() }.onFailure {
            voice?.speak("Não consegui abrir o reconhecimento de voz. Use o botão Resumo.", urgent = true)
        }
    }

    fun speakSummary() { voice?.speak(fullSummary(), urgent = true) }

    private val ptBr = Locale("pt", "BR")
    private fun km(m: Double) = String.format(ptBr, "%.1f", m / 1000)
    private fun kmh(v: Double) = String.format(ptBr, "%.0f", v)

    private fun blockText(): String? {
        val st = coach?.state ?: return null
        val plan = _live.value.workout ?: return null
        if (st.status == PowerCoach.Status.FINISHED) return "Treino estruturado concluído."
        val target = st.target ?: return null
        val sb = StringBuilder("Bloco ${st.blockIndex + 1} de ${plan.blocks.size}, alvo ${target.spoken()}, faltam ${PowerCoach.formatDuration(st.blockRemainingSec)}")
        if (blockPowerN > 0) sb.append(", média do bloco ${blockPowerSum / blockPowerN} watts")
        plan.blocks.getOrNull(st.blockIndex + 1)?.let { sb.append(". Depois, ${PowerCoach.formatDuration(it.durationSec)} a ${it.target.spoken()}") }
        return sb.append(".").toString()
    }

    /** "Me atualize": tudo de uma vez. */
    private fun fullSummary(): String {
        val l = _live.value
        val parts = mutableListOf<String>()
        blockText()?.let { parts += it }
        l.powerAvg?.let { parts += "Potência $it watts, média ${stats.avgPower}." }
        parts += "Velocidade ${kmh(l.speedKmh)}, média ${kmh(stats.avgSpeedKmh)} quilômetros por hora."
        l.heartRate?.let { parts += "Batimento $it." }
        l.cadence?.let { parts += "Cadência $it." }
        parts += "${km(stats.distanceM)} quilômetros em ${l.elapsedSec / 60} minutos."
        return parts.joinToString(" ")
    }

    private fun answer(q: VoiceQuery) {
        val l = _live.value
        val text = when (q) {
            VoiceQuery.SUMMARY, VoiceQuery.UNKNOWN -> fullSummary()
            VoiceQuery.BLOCK -> blockText() ?: "Você está no pedal livre, sem blocos. ${l.elapsedSec / 60} minutos de treino."
            VoiceQuery.SPEED -> "Velocidade ${kmh(l.speedKmh)}, média ${kmh(stats.avgSpeedKmh)}, máxima ${kmh(stats.maxSpeedKmh)} quilômetros por hora."
            VoiceQuery.POWER -> l.powerAvg?.let { p ->
                val zone = CogganZones.zoneFor(p, prefs.profile.value.ftp)
                "Potência $p watts, zona $zone. Média ${stats.avgPower} watts." +
                    (if (blockPowerN > 0) " Média do bloco ${blockPowerSum / blockPowerN}." else "")
            } ?: "Sem sinal do medidor de potência."
            VoiceQuery.HEART_RATE -> l.heartRate?.let { "Batimento $it, média ${stats.avgHr}." } ?: "Sem sinal da cinta cardíaca."
            VoiceQuery.CADENCE -> l.cadence?.let { "Cadência $it, média ${stats.avgCadence}." } ?: "Sem sinal de cadência."
            VoiceQuery.DISTANCE -> "${km(stats.distanceM)} quilômetros."
            VoiceQuery.TIME -> "${PowerCoach.formatDuration(l.elapsedSec)} de treino." +
                (blockText()?.let { " $it" } ?: "")
        }
        voice?.speak(text, urgent = true)
    }

    private fun summaryText(elapsed: Int = _live.value.elapsedSec): String {
        val br = Locale("pt", "BR")
        val parts = mutableListOf<String>()
        parts += String.format(br, "%.1f quilômetros", stats.distanceM / 1000)
        parts += "${elapsed / 60} minutos"
        if (stats.avgSpeedKmh > 0) parts += String.format(br, "média de %.1f quilômetros por hora", stats.avgSpeedKmh)
        if (stats.avgPower > 0) parts += "potência média ${stats.avgPower} watts"
        if (stats.avgHr > 0) parts += "batimento médio ${stats.avgHr}"
        return parts.joinToString(", ") + "."
    }
}
