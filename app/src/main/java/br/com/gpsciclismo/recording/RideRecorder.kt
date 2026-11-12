package br.com.gpsciclismo.recording

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import br.com.gpsciclismo.ble.BleSensorManager
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.RideStats
import br.com.gpsciclismo.core.RollingAverage
import br.com.gpsciclismo.core.WorkoutPlan
import br.com.gpsciclismo.data.AppDatabase
import br.com.gpsciclismo.data.Prefs
import br.com.gpsciclismo.data.RideEntity
import br.com.gpsciclismo.data.RidePointEntity
import br.com.gpsciclismo.voice.VoiceAnnouncer
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
    val power3s: Int? = null,
    val avgPower: Int = 0,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    val gpsAccuracyM: Float? = null,
    val workout: WorkoutPlan? = null,
    val coach: PowerCoach.State? = null,
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
    private var stats = RideStats()
    private var coach: PowerCoach? = null
    private val power3s = RollingAverage(3000)
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
    fun start(plan: WorkoutPlan?) {
        if (_live.value.status != RecStatus.IDLE) return
        pendingPlan = plan
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
    }

    private var pendingPlan: WorkoutPlan? = null

    @SuppressLint("MissingPermission")
    internal fun begin() {
        if (_live.value.status != RecStatus.IDLE) return
        val plan = pendingPlan
        stats = RideStats()
        power3s.clear()
        buffer.clear()
        lastLocation = null
        startedAt = System.currentTimeMillis()
        val coachSettings = prefs.coach.value
        voice = VoiceAnnouncer(context).also { it.enabled = coachSettings.voiceEnabled }
        coach = plan?.let {
            PowerCoach(it, PowerCoach.Settings(
                ftp = prefs.profile.value.ftp,
                tolerancePct = coachSettings.tolerancePct,
                alertDelaySec = coachSettings.alertDelaySec,
                repeatEverySec = coachSettings.repeatEverySec,
            ))
        }
        sensors.connectSaved()
        runCatching {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(1000)
                .build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }
        _live.value = LiveRide(status = RecStatus.RECORDING, workout = plan)
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                db.rides().insertRide(RideEntity(uuid = UUID.randomUUID().toString(), startedAt = startedAt, workoutName = plan?.name))
            }
            _live.value = _live.value.copy(rideId = id)
        }
        if (plan == null) voice?.speak("Treino iniciado.")
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
        _live.value = _live.value.copy(status = RecStatus.PAUSED)
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
        val l = lastLocation ?: return null
        if (now - l.time > 4000 || !l.hasSpeed()) return 0.0
        return l.speed * 3.6
    }

    private fun tick() {
        val cur = _live.value
        val now = System.currentTimeMillis()
        val r = sensors.readings.value
        val pw = r.powerNow(now)
        if (pw != null) power3s.add(now, pw)
        val avg3 = power3s.average(now)
        if (cur.status != RecStatus.RECORDING) {
            _live.value = cur.copy(power3s = avg3, heartRate = r.heartRateNow(now), cadence = r.cadenceNow(now))
            return
        }
        val elapsed = cur.elapsedSec + 1
        val speed = currentSpeed(now)
        val hr = r.heartRateNow(now)
        val cad = r.cadenceNow(now)
        stats.tickSecond(speed, pw, hr, cad)

        coach?.let { c ->
            val phrases = c.tick(elapsed - 1, avg3)
            phrases.forEach { p ->
                when {
                    p.startsWith("Tá abaixo") -> voice?.beep(high = true)
                    p.startsWith("Tá muito acima") -> voice?.beep(high = false)
                }
                voice?.speak(p)
            }
        }
        val summaryMin = prefs.coach.value.summaryEveryMin
        if (summaryMin > 0 && elapsed % (summaryMin * 60) == 0) voice?.speak(summaryText(elapsed))

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
            power3s = avg3,
            avgPower = stats.avgPower,
            heartRate = hr,
            cadence = cad,
            coach = coach?.state,
        )
    }

    private suspend fun flush() {
        val copy = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (copy.isNotEmpty()) db.rides().insertPoints(copy)
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
