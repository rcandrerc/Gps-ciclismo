package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.data.CoachSettings
import br.com.gpsciclismo.data.Profile

@Composable
fun ProfileScreen(app: GpsCiclismoApp, onBack: () -> Unit) {
    val p = app.prefs.profile.value
    val c = app.prefs.coach.value
    var name by remember { mutableStateOf(p.name) }
    var weight by remember { mutableStateOf(if (p.weightKg > 0) fmt1(p.weightKg) else "") }
    var height by remember { mutableStateOf(if (p.heightCm > 0) p.heightCm.toString() else "") }
    var ftp by remember { mutableStateOf(p.ftp.toString()) }
    var tolerance by remember { mutableStateOf(c.tolerancePct.toString()) }
    var delay by remember { mutableStateOf(c.alertDelaySec.toString()) }
    var repeat by remember { mutableStateOf(c.repeatEverySec.toString()) }
    var summary by remember { mutableStateOf(c.summaryEveryMin.toString()) }
    var voice by remember { mutableStateOf(c.voiceEnabled) }

    val ftpInt = ftp.toIntOrNull() ?: 0

    Column(Modifier.fillMaxSize()) {
        BackBar("Perfil e voz", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Atleta", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Peso (kg)", weight, Modifier.weight(1f), decimal = true) { weight = it }
                NumField("Altura (cm)", height, Modifier.weight(1f)) { height = it }
                NumField("FTP (W)", ftp, Modifier.weight(1f)) { ftp = it }
            }

            if (ftpInt > 0) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Zonas do Coggan", style = MaterialTheme.typography.titleSmall)
                        CogganZones.zones.forEach { z ->
                            val r = CogganZones.range(z.number, ftpInt)
                            Row(Modifier.fillMaxWidth()) {
                                Text("Z${z.number}  ${z.name}", Modifier.weight(1f))
                                Text(if (z.number == 7) "> ${r.first} W" else "${r.first}–${r.last} W")
                            }
                        }
                    }
                }
            }

            Text("Treinador por voz", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avisos falados", Modifier.weight(1f))
                Switch(voice, { voice = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Margem ± %", tolerance, Modifier.weight(1f)) { tolerance = it }
                NumField("Avisar após (s)", delay, Modifier.weight(1f)) { delay = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Repetir a cada (s)", repeat, Modifier.weight(1f)) { repeat = it }
                NumField("Resumo a cada (min)", summary, Modifier.weight(1f)) { summary = it }
            }
            Text(
                "A margem vale para alvos em watts: com 5% e alvo de 240 W, o app só avisa abaixo de 228 W ou acima de 252 W. " +
                    "Alvos em zona usam a faixa da zona. Resumo 0 desliga o resumo periódico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    app.prefs.saveProfile(Profile(
                        name = name.trim(),
                        weightKg = weight.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        heightCm = height.toIntOrNull() ?: 0,
                        ftp = ftpInt.takeIf { it > 0 } ?: p.ftp,
                    ))
                    app.prefs.saveCoach(CoachSettings(
                        tolerancePct = tolerance.toIntOrNull()?.coerceIn(1, 30) ?: c.tolerancePct,
                        alertDelaySec = delay.toIntOrNull()?.coerceIn(1, 60) ?: c.alertDelaySec,
                        repeatEverySec = repeat.toIntOrNull()?.coerceIn(5, 300) ?: c.repeatEverySec,
                        summaryEveryMin = summary.toIntOrNull()?.coerceIn(0, 120) ?: c.summaryEveryMin,
                        voiceEnabled = voice,
                    ))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar") }
        }
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
