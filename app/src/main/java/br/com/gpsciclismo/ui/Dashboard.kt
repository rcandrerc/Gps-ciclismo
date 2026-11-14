package br.com.gpsciclismo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.DashboardLayout
import br.com.gpsciclismo.core.LiveField
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.Tile
import br.com.gpsciclismo.core.TileSize
import br.com.gpsciclismo.data.Profile
import br.com.gpsciclismo.recording.LiveRide

private data class FieldValue(val value: String, val unit: String, val color: Color = Color.Unspecified, val label: String? = null)

private fun valueOf(f: LiveField, live: LiveRide, profile: Profile): FieldValue {
    // Cor da potência pelo alvo do bloco atual (só em treino com alvo), usando a média suavizada.
    val pColor = powerStatusColor(PowerCoach.compare(live.powerAvg, live.coach?.range))
    val win = "${live.powerWindowSec}s"
    return when (f) {
    LiveField.SPEED -> FieldValue(fmt1(live.speedKmh), "km/h")
    LiveField.AVG_SPEED -> FieldValue(fmt1(live.avgSpeedKmh), "km/h")
    LiveField.MAX_SPEED -> FieldValue(fmt1(live.maxSpeedKmh), "km/h")
    LiveField.DISTANCE -> FieldValue(fmtKm(live.distanceM), "km")
    LiveField.TIME -> FieldValue(fmtTime(live.elapsedSec), "")
    LiveField.POWER_3S -> FieldValue(
        fmtOpt(live.powerAvg), live.powerAvg?.let { "W · Z${CogganZones.zoneFor(it, profile.ftp)}" } ?: "W", pColor,
        label = if (live.powerWindowSec <= 1) "Potência" else "Potência $win",
    )
    LiveField.POWER_NOW -> FieldValue(fmtOpt(live.powerNow), "W")
    LiveField.AVG_POWER -> FieldValue(live.avgPower.toString(), "W")
    LiveField.MAX_POWER -> FieldValue(live.maxPower.toString(), "W")
    LiveField.W_PER_KG -> FieldValue(
        if (profile.weightKg > 0 && live.powerAvg != null) fmt1(live.powerAvg / profile.weightKg) else "--", "W/kg", pColor,
        label = if (live.powerWindowSec <= 1) "W/kg" else "W/kg ($win)",
    )
    LiveField.POWER_ZONE -> {
        val z = live.powerAvg?.let { CogganZones.zoneFor(it, profile.ftp) }
        FieldValue(z?.let { "Z$it" } ?: "--", z?.let { CogganZones.zone(it).name } ?: "", pColor)
    }
    LiveField.HEART_RATE -> FieldValue(fmtOpt(live.heartRate), "bpm", Color(0xFFFF6B6B))
    LiveField.AVG_HR -> FieldValue(if (live.avgHr > 0) live.avgHr.toString() else "--", "bpm")
    LiveField.CADENCE -> FieldValue(fmtOpt(live.cadence), "rpm")
    LiveField.KCAL -> FieldValue(live.kcal.toString(), "kcal")
    }
}

/**
 * Painel de campos personalizável. Segure qualquer campo para editar:
 * X remove, setas mudam de posição, o botão de tamanho alterna pequeno / largo / grande,
 * e "Adicionar campo" coloca campos novos.
 */
@Composable
fun Dashboard(app: GpsCiclismoApp, live: LiveRide, profile: Profile) {
    val layout by app.prefs.dashboard.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun save(l: DashboardLayout) = app.prefs.saveDashboard(l)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editing) {
            Text(
                "Editando a tela: X remove, setas mudam a posição, ⤢ muda o tamanho.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Text(" Adicionar campo")
                }
                Button(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("Pronto") }
            }
        }

        if (layout.tiles.isEmpty()) {
            Text(
                "Nenhum campo na tela. " + if (editing) "Toque em \"Adicionar campo\"." else "Segure aqui para editar.",
                modifier = Modifier.fillMaxWidth().padding(16.dp)
                    .combinedClickableCompat(onLongClick = { editing = true }),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        layout.rows().forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (index, tile) ->
                    TileView(
                        tile = tile,
                        value = valueOf(tile.field, live, profile),
                        editing = editing,
                        modifier = Modifier.weight(1f),
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            editing = true
                        },
                        onRemove = { save(layout.remove(index)) },
                        onResize = { save(layout.resize(index)) },
                        onMoveBack = { save(layout.move(index, index - 1)) },
                        onMoveForward = { save(layout.move(index, index + 1)) },
                        canMoveBack = index > 0,
                        canMoveForward = index < layout.tiles.lastIndex,
                    )
                }
            }
        }

        if (editing) {
            TextButton(onClick = { save(DashboardLayout.DEFAULT) }) { Text("Voltar ao layout padrão") }
        }
    }

    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Adicionar campo") },
        text = {
            val options = layout.available()
            if (options.isEmpty()) Text("Todos os campos já estão na tela.")
            else LazyColumn {
                items(options) { f ->
                    TextButton(onClick = { save(layout.add(f)); adding = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(f.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = false }) { Text("Fechar") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = {}, onLongClick = onLongClick)

@Composable
private fun TileView(
    tile: Tile,
    value: FieldValue,
    editing: Boolean,
    modifier: Modifier,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    onResize: () -> Unit,
    onMoveBack: () -> Unit,
    onMoveForward: () -> Unit,
    canMoveBack: Boolean,
    canMoveForward: Boolean,
) {
    val fontSize = when (tile.size) {
        TileSize.SMALL -> 30.sp
        TileSize.WIDE -> 40.sp
        TileSize.BIG -> 64.sp
    }
    Box(modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickableCompat(onLongClick = onLongPress),
            colors = if (editing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            else CardDefaults.cardColors(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = if (tile.size == TileSize.BIG) 18.dp else 10.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(value.label ?: tile.field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.value, fontSize = fontSize, fontWeight = FontWeight.Bold, color = value.color, maxLines = 1)
                if (value.unit.isNotEmpty()) Text(value.unit, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallIcon(Icons.AutoMirrored.Filled.ArrowBack, "Mover para trás", canMoveBack, onMoveBack)
                        SmallIcon(Icons.Filled.OpenInFull, "Mudar tamanho", true, onResize)
                        SmallIcon(Icons.AutoMirrored.Filled.ArrowForward, "Mover para frente", canMoveForward, onMoveForward)
                    }
                }
            }
        }
        if (editing) {
            FilledTonalIconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp),
            ) { Icon(Icons.Filled.Close, "Remover", Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, desc, Modifier.size(18.dp))
    }
}
