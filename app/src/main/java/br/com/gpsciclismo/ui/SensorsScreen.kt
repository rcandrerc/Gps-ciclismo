package br.com.gpsciclismo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.ble.BleSensorManager
import br.com.gpsciclismo.ble.LinkState
import br.com.gpsciclismo.data.SavedSensor
import br.com.gpsciclismo.data.SensorKind
import br.com.gpsciclismo.recording.RecStatus

@Composable
fun SensorsScreen(app: GpsCiclismoApp, onBack: () -> Unit) {
    val ble = app.sensors
    val saved by app.prefs.sensors.collectAsStateWithLifecycle()
    val links by ble.links.collectAsStateWithLifecycle()
    val found by ble.found.collectAsStateWithLifecycle()
    val scanning by ble.scanning.collectAsStateWithLifecycle()
    val readings by ble.readings.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ble.hasPermissions()) ble.connectSaved()
    }
    LaunchedEffect(Unit) {
        if (ble.hasPermissions()) ble.connectSaved() else permLauncher.launch(BleSensorManager.requiredPermissions())
    }
    DisposableEffect(Unit) {
        onDispose {
            ble.stopScan()
            if (app.recorder.live.value.status == RecStatus.IDLE) ble.disconnectAll()
        }
    }

    // Tipo de sensor que o ciclista tocou para escolher (filtra a busca).
    var wanted by remember { mutableStateOf<SensorKind?>(null) }
    fun search(kind: SensorKind?) {
        wanted = kind
        if (!ble.hasPermissions()) { permLauncher.launch(BleSensorManager.requiredPermissions()); return }
        ble.stopScan()
        ble.startScan()
    }
    val shown = found.filter { d -> wanted == null || wanted in d.kinds }
    val trainerSaved = saved[SensorKind.TRAINER] != null

    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize()) {
        BackBar("Sensores", onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!ble.isBluetoothOn()) item {
                Text("Ligue o Bluetooth do celular.", color = Red)
            }
            items(SensorKind.entries) { kind ->
                val s = saved[kind]
                val state = s?.let { links[it.address] } ?: LinkState.DISCONNECTED
                val value = when (kind) {
                    SensorKind.HEART_RATE -> readings.heartRateNow(now)?.let { "$it bpm" }
                    SensorKind.CADENCE -> readings.cadenceNow(now)?.let { "$it rpm" }
                    SensorKind.POWER -> readings.powerNow(now)?.let { "$it W" }
                    SensorKind.TRAINER -> readings.trainerSpeedNow(now)?.let { "${fmt1(it)} km/h" }
                }
                // O rolo smart já manda potência e cadência, se não houver outro sensor para isso.
                val fromTrainer = s == null && trainerSaved && (kind == SensorKind.POWER || kind == SensorKind.CADENCE)
                Card(onClick = { search(kind) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (fromTrainer) "Vem do rolo smart"
                                else if (s == null) "Toque para escolher"
                                else "${s.name} · " + when (state) {
                                    LinkState.CONNECTED -> "conectado"
                                    LinkState.CONNECTING -> "conectando…"
                                    LinkState.DISCONNECTED -> "desconectado"
                                },
                                color = if (state == LinkState.CONNECTED) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (value != null) Text(value, style = MaterialTheme.typography.headlineSmall)
                        }
                        if (s != null) TextButton(onClick = {
                            app.prefs.removeSensor(kind); ble.refreshConnections()
                        }) { Text("Remover") }
                        else TextButton(onClick = { search(kind) }) { Text("Escolher") }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        if (!ble.hasPermissions()) permLauncher.launch(BleSensorManager.requiredPermissions())
                        else if (scanning) ble.stopScan() else search(null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (scanning) "Parar busca" + (wanted?.let { " (${it.label})" } ?: "")
                        else "Buscar todos os sensores"
                    )
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    "Pode escolher um sensor de cada tipo, todos ao mesmo tempo (ex.: rolo smart + cinta cardíaca). " +
                        "Acorde os sensores antes de buscar: gire o pedivela e molhe e coloque a cinta no peito. " +
                        "Sensor já conectado não aparece na busca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (scanning && shown.isEmpty()) item {
                Text("Procurando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.address }) { d ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text("${d.address} · sinal ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        Column {
                            SensorKind.entries.filter { it in d.kinds && (wanted == null || it == wanted) }.forEach { k ->
                                val inUse = saved[k]?.address == d.address
                                AssistChip(
                                    onClick = {
                                        app.prefs.saveSensor(SavedSensor(k, d.address, d.name))
                                        ble.connectSaved(); ble.refreshConnections()
                                        if (wanted == k) { wanted = null; ble.stopScan() }
                                    },
                                    label = { Text(if (inUse) "✓ ${k.label}" else "Usar p/ ${k.label}") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
