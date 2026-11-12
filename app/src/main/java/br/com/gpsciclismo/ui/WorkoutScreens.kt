package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.WorkoutBlock
import br.com.gpsciclismo.core.WorkoutPlan
import br.com.gpsciclismo.data.WorkoutEntity
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val workoutJson = Json { ignoreUnknownKeys = true }

fun WorkoutEntity.plan(): WorkoutPlan? = runCatching { workoutJson.decodeFromString<WorkoutPlan>(planJson) }.getOrNull()

fun WorkoutPlan.shortDescription(): String =
    blocks.joinToString(" · ") { "${it.durationSec / 60}' ${it.target.label()}" }

@Composable
fun WorkoutsScreen(app: GpsCiclismoApp, nav: NavController) {
    val flow = remember { app.db.workouts().workouts() }
    val workouts by flow.collectAsStateWithLifecycle(emptyList())
    val start = rememberStartRide(app, nav)
    Scaffold(
        topBar = { BackBar("Treinos por zonas", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("workout/0") }) {
                Icon(Icons.Filled.Add, null); Text(" Novo treino")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workouts.isEmpty()) item {
                Text(
                    "Nenhum treino ainda. Toque em \"Novo treino\" e monte os blocos, por exemplo 5 min a 240 W e 5 min a 130 W.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(workouts, key = { it.id }) { w ->
                val plan = w.plan()
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            if (plan != null) {
                                Text("${fmtTime(plan.totalSec)} · ${plan.blocks.size} blocos", style = MaterialTheme.typography.bodySmall)
                                Text(plan.shortDescription(), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { nav.navigate("workout/${w.id}") }) { Icon(Icons.Filled.Edit, "Editar") }
                        FilledIconButton(onClick = { if (plan != null) start(plan) }) { Icon(Icons.Filled.PlayArrow, "Iniciar") }
                    }
                }
            }
        }
    }
}

private enum class TargetType(val label: String) { WATTS("Watts"), ZONE("Zona"), FREE("Livre") }

private data class EditBlock(val min: String, val sec: String, val type: TargetType, val value: String) {
    fun toBlock(): WorkoutBlock? {
        val dur = (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
        if (dur <= 0) return null
        val target = when (type) {
            TargetType.WATTS -> PowerTarget.Watts(value.toIntOrNull()?.takeIf { it > 0 } ?: return null)
            TargetType.ZONE -> PowerTarget.Zone(value.toIntOrNull()?.takeIf { it in 1..7 } ?: return null)
            TargetType.FREE -> PowerTarget.Free
        }
        return WorkoutBlock(dur, target)
    }

    companion object {
        fun from(b: WorkoutBlock): EditBlock {
            val (t, v) = when (val x = b.target) {
                is PowerTarget.Watts -> TargetType.WATTS to x.watts.toString()
                is PowerTarget.Zone -> TargetType.ZONE to x.zone.toString()
                PowerTarget.Free -> TargetType.FREE to ""
            }
            return EditBlock((b.durationSec / 60).toString(), (b.durationSec % 60).toString(), t, v)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorScreen(app: GpsCiclismoApp, id: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<EditBlock>() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id != 0L) {
            app.db.workouts().workout(id)?.let { w ->
                name = w.name
                w.plan()?.blocks?.map(EditBlock::from)?.let { blocks.clear(); blocks.addAll(it) }
            }
        } else if (blocks.isEmpty()) {
            blocks += EditBlock("5", "0", TargetType.WATTS, "240")
            blocks += EditBlock("5", "0", TargetType.WATTS, "130")
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(if (id == 0L) "Novo treino" else "Editar treino", onBack) {
            if (id != 0L) IconButton(onClick = {
                scope.launch { app.db.workouts().delete(id); onBack() }
            }) { Icon(Icons.Filled.Delete, "Excluir") }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Nome do treino") }, modifier = Modifier.fillMaxWidth())
            }
            itemsIndexed(blocks) { i, b ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Bloco ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { blocks.add(i + 1, b) }) { Icon(Icons.Filled.ContentCopy, "Duplicar") }
                            IconButton(onClick = { blocks.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remover") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("Min", b.min, Modifier.width(80.dp)) { blocks[i] = b.copy(min = it) }
                            NumField("Seg", b.sec, Modifier.width(80.dp)) { blocks[i] = b.copy(sec = it) }
                            if (b.type != TargetType.FREE) {
                                NumField(if (b.type == TargetType.WATTS) "Watts" else "Zona (1-7)", b.value, Modifier.weight(1f)) {
                                    blocks[i] = b.copy(value = it)
                                }
                            }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            TargetType.entries.forEachIndexed { j, t ->
                                SegmentedButton(
                                    selected = b.type == t,
                                    onClick = { blocks[i] = b.copy(type = t, value = if (t == b.type) b.value else "") },
                                    shape = SegmentedButtonDefaults.itemShape(j, TargetType.entries.size),
                                ) { Text(t.label) }
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { blocks += EditBlock("5", "0", TargetType.WATTS, "") }, modifier = Modifier.weight(1f)) {
                        Text("+ Bloco")
                    }
                    OutlinedButton(
                        onClick = { if (blocks.size >= 2) blocks.addAll(blocks.takeLast(2)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Repetir últimos 2") }
                }
            }
            item {
                val parsed = blocks.map { it.toBlock() }
                val total = parsed.filterNotNull().sumOf { it.durationSec }
                Text("Duração total: ${fmtTime(total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = Red) }
                Button(
                    onClick = {
                        when {
                            name.isBlank() -> error = "Dê um nome ao treino."
                            blocks.isEmpty() -> error = "Adicione pelo menos um bloco."
                            parsed.any { it == null } -> error = "Confira os blocos: tempo maior que zero, watts ou zona de 1 a 7."
                            else -> scope.launch {
                                val plan = WorkoutPlan(name.trim(), parsed.filterNotNull())
                                app.db.workouts().save(WorkoutEntity(id = id, name = plan.name, planJson = workoutJson.encodeToString(plan)))
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) { Text("Salvar treino") }
            }
        }
    }
}
