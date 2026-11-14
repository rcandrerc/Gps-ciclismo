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
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import br.com.gpsciclismo.core.RollingAverage
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import br.com.gpsciclismo.voice.VoiceAnnouncer
import br.com.gpsciclismo.voice.VoiceOption
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
    var voiceName by remember { mutableStateOf(c.voiceName) }
    var rate by remember { mutableFloatStateOf(c.speechRate) }
    var powerDisplay by remember { mutableStateOf(c.powerDisplaySec) }
    var powerAlert by remember { mutableStateOf(c.powerAlertSec) }
    var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    val context = LocalContext.current
    val previewer = remember { VoiceAnnouncer(context.applicationContext, onReady = { voices = it.portugueseVoices() }) }
    DisposableEffect(Unit) { onDispose { previewer.shutdown() } }
    val sample = "Bloco 2 de 6. Tá abaixo, sobe pra 240 watts."

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

            Text("Leitura da potência", style = MaterialTheme.typography.titleMedium)
            Text(
                "A potência instantânea pula muito a cada pedalada. Como no Garmin e no Wahoo, o app mostra a média " +
                    "dos últimos segundos. 3 s é o mais usado; 5 s ou 10 s ficam mais estáveis, mas demoram mais para reagir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondsChoice("Na tela", powerDisplay) { powerDisplay = it }
            SecondsChoice("Nos avisos de zona", powerAlert) { powerAlert = it }
            Text(
                "Os avisos por voz usam a média própria deles: mais longa evita aviso falso numa pedalada mais forte. " +
                    "A cor do número (verde na zona, cinza abaixo, vermelho acima) segue a média da tela. O treino é gravado segundo a segundo, sem média.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Treinador por voz", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avisos automáticos (blocos, fora da zona, resumo)", Modifier.weight(1f))
                Switch(voice, { voice = it })
            }
            Text("Voz", style = MaterialTheme.typography.titleSmall)
            Text(
                "Escolha a voz e toque em Ouvir. As de qualidade alta costumam soar mais naturais. " +
                    "Para baixar mais vozes: Configurações do Android > Acessibilidade > Saída de conversão de texto em voz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (voices.isEmpty()) Text("Carregando vozes…", style = MaterialTheme.typography.bodySmall)
            (listOf<VoiceOption?>(null) + voices).forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = voiceName == v?.name, onClick = { voiceName = v?.name })
                    Text(v?.label ?: "Padrão do celular", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { voiceName = v?.name; previewer.preview(v?.name, rate, sample) }) { Text("Ouvir") }
                }
            }
            Text("Velocidade da fala: ${fmt1(rate.toDouble())}x", style = MaterialTheme.typography.bodySmall)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.6f, steps = 8,
                onValueChangeFinished = { previewer.preview(voiceName, rate, sample) })

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
                        voiceName = voiceName,
                        speechRate = rate,
                        powerDisplaySec = powerDisplay,
                        powerAlertSec = powerAlert,
                    ))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar") }
        }
    }
}

@Composable
private fun SecondsChoice(label: String, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RollingAverage.OPTIONS.forEach { sec ->
                val text = if (sec == 1) "Instant." else "$sec s"
                val m = Modifier.weight(1f)
                if (sec == selected) Button(onClick = { onSelect(sec) }, modifier = m, contentPadding = PaddingValues(4.dp)) { Text(text, maxLines = 1) }
                else OutlinedButton(onClick = { onSelect(sec) }, modifier = m, contentPadding = PaddingValues(4.dp)) { Text(text, maxLines = 1) }
            }
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
