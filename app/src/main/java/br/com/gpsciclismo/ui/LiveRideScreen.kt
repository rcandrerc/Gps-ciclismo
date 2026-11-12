package br.com.gpsciclismo.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import br.com.gpsciclismo.recording.RecStatus
import kotlinx.coroutines.launch

@Composable
fun LiveRideScreen(app: GpsCiclismoApp, nav: NavController) {
    val live by app.recorder.live.collectAsStateWithLifecycle()
    val profile by app.prefs.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    LaunchedEffect(live.status) {
        if (live.status == RecStatus.IDLE && !stopping) nav.navigate("home") { popUpTo(0) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                live.workout?.name ?: "Pedal livre",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            val gps = live.gpsAccuracyM
            Text(
                if (gps == null) "GPS procurando…" else "GPS ±${gps.toInt()} m",
                color = if (gps != null && gps <= 25f) Green else Amber,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // Painel do treino por zonas
        val coach = live.coach
        val plan = live.workout
        if (plan != null && coach != null && coach.status != PowerCoach.Status.FINISHED && coach.target != null) {
            val color = when (coach.status) {
                PowerCoach.Status.IN_ZONE -> Green
                PowerCoach.Status.BELOW -> Blue
                PowerCoach.Status.ABOVE -> Red
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Column(
                Modifier.fillMaxWidth().background(color.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Bloco ${coach.blockIndex + 1} de ${plan.blocks.size} · faltam ${fmtTime(coach.blockRemainingSec)}")
                Text(
                    "Alvo ${coach.target.label()}" + (coach.range?.let { "  (${it.first}–${it.last} W)" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    fmtOpt(live.power3s),
                    fontSize = 72.sp, fontWeight = FontWeight.Bold,
                    color = if (coach.status == PowerCoach.Status.NO_TARGET) MaterialTheme.colorScheme.onSurface else color,
                )
                Text(
                    when (coach.status) {
                        PowerCoach.Status.IN_ZONE -> "na zona"
                        PowerCoach.Status.BELOW -> "abaixo: suba"
                        PowerCoach.Status.ABOVE -> "acima: baixe"
                        else -> if (live.power3s == null) "sem sinal do medidor de potência" else "watts (3 s)"
                    },
                    color = if (coach.status == PowerCoach.Status.NO_TARGET) MaterialTheme.colorScheme.onSurfaceVariant else color,
                )
                plan.blocks.getOrNull(coach.blockIndex + 1)?.let {
                    Text("Próximo: ${fmtTime(it.durationSec)} a ${it.target.label()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (plan != null && coach?.status == PowerCoach.Status.FINISHED) {
            Text("Treino estruturado concluído! Continue gravando ou finalize.", color = Green)
        }

        Dashboard(app, live, profile)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (live.status == RecStatus.PAUSED) {
                Button(onClick = { app.recorder.resume() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Continuar") }
            } else {
                OutlinedButton(onClick = { app.recorder.pause() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Pausar") }
            }
            Button(
                onClick = { confirmStop = true },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("Finalizar") }
        }
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
