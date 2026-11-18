package br.com.gpsciclismo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.saveable.rememberSaveable
import br.com.gpsciclismo.core.SceneTrack
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilledTonalButton
import br.com.gpsciclismo.recording.LiveRide
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.FtpTestTracker
import br.com.gpsciclismo.recording.RecStatus
import br.com.gpsciclismo.ble.TrainerControl
import kotlinx.coroutines.launch

@Composable
fun LiveRideScreen(app: GpsCiclismoApp, nav: NavController) {
    val live by app.recorder.live.collectAsStateWithLifecycle()
    val profile by app.prefs.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    // Cena 2D (ciclista na estrada): padrão no indoor; a escolha fica lembrada.
    // (o tipo de treino chega um instante depois de abrir a tela, por isso a escolha começa vazia)
    var sceneChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val scene = sceneChoice ?: (live.setup.indoor && app.prefs.sceneView)
    val setScene: (Boolean) -> Unit = { v -> sceneChoice = v; if (live.setup.indoor) app.prefs.sceneView = v }
    val track = remember(live.workout, profile.ftp, live.offsetW) { SceneTrack.of(live.workout, profile.ftp, live.offsetW) }

    LaunchedEffect(live.status) {
        if (live.status == RecStatus.IDLE && !stopping) nav.navigate("home") { popUpTo(0) }
    }

    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp   // tablet deitado: duas colunas
        val roomy = maxWidth >= 600.dp  // tablet em pé: mais campos por linha
        if (wide && scene) {
            SceneRideView(app, live, profile.ftp, track, onShowPanel = { setScene(false) }, onStop = { confirmStop = true })
        } else if (wide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RideHeader(app, live, scene) { setScene(!scene) }
                    TimeStrip(app, live)
                    PowerPanel(live, numberSize = 96)
                    IntensityControls(app, live)
                    VoiceButtons(app)
                    RideControls(app, live) { confirmStop = true }
                }
                Column(
                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { Dashboard(app, live, profile, perRow = 3) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (roomy) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RideHeader(app, live, scene) { setScene(!scene) }
                if (scene) SceneBanner(live, track, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                TimeStrip(app, live)
                PowerPanel(live, numberSize = if (roomy) 96 else 72)
                IntensityControls(app, live)
                Dashboard(app, live, profile, perRow = if (roomy) 3 else 2)
                Spacer(Modifier.height(4.dp))
                VoiceButtons(app)
                RideControls(app, live) { confirmStop = true }
            }
        }
    }
    // Contagem regressiva por cima de tudo (vale também para a cena do tablet deitado).
    live.countdown?.let { CountdownOverlay(it) }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Finalizar treino?") },
        text = { Text("O treino será salvo no histórico.") },
        confirmButton = {
            TextButton(onClick = {
                confirmStop = false
                stopping = true
                scope.launch {
                    val id = app.recorder.stop()
                    nav.navigate(if (id != null && id != 0L) "ride/$id" else "home") { popUpTo("home") }
                }
            }) { Text("Finalizar e salvar") }
        },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancelar") } },
    )
}

/** Número grande da contagem antes de começar / continuar. */
@Composable
private fun CountdownOverlay(n: Int) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PREPARA", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("$n", color = Green, fontSize = 160.sp, fontWeight = FontWeight.Bold, style = Tabular)
        }
    }
}

@Composable
private fun RideHeader(app: GpsCiclismoApp, live: LiveRide, sceneOn: Boolean, onToggleScene: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onToggleScene) { Text(if (sceneOn) "Esconder cena" else "Ver cena") }
        Text(
            live.workout?.name ?: "Pedal livre",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
        )
        if (live.status == RecStatus.PAUSED) Text(
            "PAUSADO  ", color = Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        )
        if (live.setup.indoor) {
            val trainer by app.sensors.trainer.collectAsStateWithLifecycle()
            Text(
                when {
                    !live.ergOn -> "Indoor · rolo solto"
                    trainer == TrainerControl.DENIED -> "Indoor · ERG recusado"
                    trainer != TrainerControl.OK -> "Indoor · ERG conectando…"
                    live.ergWatts != null -> "Indoor · ERG ${live.ergWatts} W"
                    else -> "Indoor · ERG livre"
                },
                color = if (!live.ergOn || trainer == TrainerControl.OK) Green else Amber,
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            val gps = live.gpsAccuracyM
            Text(
                if (gps == null) "GPS procurando…" else "GPS ±${gps.toInt()} m",
                color = if (gps != null && gps <= 25f) Green else Amber,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }

}

/** Tempo do treino e, no treino por blocos, quanto falta para acabar. */
@Composable
private fun TimeStrip(app: GpsCiclismoApp, live: LiveRide) {
    val total = live.workout?.totalSec
    val remaining = total?.let { (it - live.elapsedSec).coerceAtLeast(0) }
    val paused = live.status == RecStatus.PAUSED
    Column(
        Modifier.fillMaxWidth()
            .background(if (paused) Amber.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TEMPO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtTime(live.elapsedSec), fontSize = 32.sp, style = Tabular, fontWeight = FontWeight.Bold)
            }
            // Pausar/continuar sempre à mão, perto do tempo (o tempo e os blocos congelam na pausa).
            FilledTonalButton(
                onClick = { if (paused) app.recorder.resume() else app.recorder.pause() },
                modifier = Modifier.height(52.dp).padding(horizontal = 8.dp),
                colors = if (paused) ButtonDefaults.filledTonalButtonColors(containerColor = Amber, contentColor = Color.Black)
                else ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Text(if (paused) " Continuar" else " Pausar")
            }
            if (remaining != null) Column(horizontalAlignment = Alignment.End) {
                Text("FALTAM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (remaining == 0) "fim" else fmtTime(remaining),
                    fontSize = 32.sp, style = Tabular, fontWeight = FontWeight.Bold,
                    color = if (remaining == 0) Green else Color.Unspecified,
                )
            }
        }
        if (total != null && total > 0) LinearProgressIndicator(
            progress = { (live.elapsedSec.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PowerPanel(live: LiveRide, numberSize: Int) {
    // Painel do treino por zonas
    val coach = live.coach
    val plan = live.workout
    if (plan != null && coach != null && coach.status != PowerCoach.Status.FINISHED && coach.target != null) {
        // Cor decidida pela mesma média mostrada no número (verde na zona, cinza abaixo, vermelho acima).
        val shown = PowerCoach.compare(live.powerAvg, coach.range)
        val color = when (shown) {
            PowerCoach.Status.IN_ZONE, PowerCoach.Status.BELOW, PowerCoach.Status.ABOVE -> powerStatusColor(shown)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Column(
            Modifier.fillMaxWidth().background(color.copy(alpha = 0.18f), MaterialTheme.shapes.large)
                .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.large).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bloco ${coach.blockIndex + 1} de ${plan.blocks.size} · faltam ${fmtTime(coach.blockRemainingSec)}")
            if (coach.ftpTest) Text("TESTE DE FTP", style = MaterialTheme.typography.labelLarge, color = Amber)
            Text(
                when {
                    coach.ftpTest && coach.range != null -> "Alvo ${coach.target.label()} ou mais (mín. ${coach.range.first} W)"
                    coach.ftpTest -> "Vai com tudo!"
                    else -> "Alvo ${coach.target.label()}" + (coach.range?.let { "  (${it.first}–${it.last} W)" } ?: "")
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                fmtOpt(live.powerAvg),
                fontSize = numberSize.sp, style = Tabular, fontWeight = FontWeight.Bold,
                color = if (shown == PowerCoach.Status.NO_TARGET) MaterialTheme.colorScheme.onSurface else color,
            )
            Text(
                when (shown) {
                    PowerCoach.Status.IN_ZONE -> "na zona"
                    PowerCoach.Status.BELOW -> "abaixo: suba"
                    PowerCoach.Status.ABOVE -> "acima: baixe"
                    else -> if (coach.ftpTest && live.powerAvg != null) "watts (${live.powerWindowSec} s)" else if (live.powerAvg == null) "sem sinal do medidor de potência" else "watts (${live.powerWindowSec} s)"
                },
                color = if (shown == PowerCoach.Status.NO_TARGET) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
            if (coach.ftpTest) live.testAvgPower?.let { avg ->
                Text(
                    "Média do teste $avg W · FTP ≈ ${FtpTestTracker.ftpFrom(avg)} W",
                    style = MaterialTheme.typography.titleMedium, color = Amber,
                )
            }
            plan.blocks.getOrNull(coach.blockIndex + 1)?.let {
                Text("Próximo: ${fmtTime(it.durationSec)} a ${it.target.label()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (plan != null && coach?.status == PowerCoach.Status.FINISHED) {
        Text("Treino estruturado concluído! Continue gravando ou finalize.", color = Green)
    }

}

/** No treino por blocos: −10/+10 W (como no MyWhoosh) e, no rolo, ERG liga/desliga. */
@Composable
private fun IntensityControls(app: GpsCiclismoApp, live: LiveRide) {
    if (live.workout == null || live.coach == null || live.coach.status == PowerCoach.Status.FINISHED) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { app.recorder.adjustIntensity(-10) }, modifier = Modifier.weight(1f).height(56.dp)) {
            Text("−10 W", style = MaterialTheme.typography.titleMedium)
        }
        if (live.setup.indoor) {
            val on = live.ergOn
            Button(
                onClick = { app.recorder.toggleErg() },
                colors = if (on) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1.2f).height(56.dp),
            ) { Text(if (on) "ERG ligado" else "ERG desligado", style = MaterialTheme.typography.titleSmall) }
        }
        FilledTonalButton(onClick = { app.recorder.adjustIntensity(10) }, modifier = Modifier.weight(1f).height(56.dp)) {
            Text("+10 W", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (live.offsetW != 0) Text(
        "Intensidade ajustada: ${if (live.offsetW > 0) "+" else "−"}${kotlin.math.abs(live.offsetW)} W nos alvos",
        style = MaterialTheme.typography.bodySmall, color = Amber,
    )
}

@Composable
private fun VoiceButtons(app: GpsCiclismoApp) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { app.recorder.speakSummary() }, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Filled.RecordVoiceOver, null); Text(" Resumo")
        }
        FilledTonalButton(onClick = { app.recorder.askByVoice() }, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Filled.Mic, null); Text(" Perguntar")
        }
    }
    Text(
        "No fone: 1 toque = resumo, 2 toques = perguntar (velocidade, potência, quanto falta…).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RideControls(app: GpsCiclismoApp, live: LiveRide, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live.status == RecStatus.PAUSED) {
            Button(onClick = { app.recorder.resume() }, modifier = Modifier.weight(1f).height(58.dp),
                shape = MaterialTheme.shapes.large) { Text("Continuar", style = MaterialTheme.typography.titleMedium) }
        } else {
            OutlinedButton(onClick = { app.recorder.pause() }, modifier = Modifier.weight(1f).height(58.dp),
                shape = MaterialTheme.shapes.large) { Text("Pausar", style = MaterialTheme.typography.titleMedium) }
        }
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
            modifier = Modifier.weight(1f).height(58.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("Finalizar", style = MaterialTheme.typography.titleMedium) }
    }
}
