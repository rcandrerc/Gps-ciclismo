package br.com.gpsciclismo.ui

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.WeightLog
import br.com.gpsciclismo.data.WeightEntity
import br.com.gpsciclismo.data.WeightPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val dayFmt = SimpleDateFormat("EEE dd/MM/yyyy HH:mm", Locale("pt", "BR"))
private val shortFmt = SimpleDateFormat("dd/MM", Locale("pt", "BR"))

/** Grava a pesagem (foto opcional, já reduzida) e atualiza o peso do perfil se for a mais recente. */
suspend fun saveWeighIn(app: GpsCiclismoApp, kg: Double, photo: File?) {
    val uuid = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val photoPath = withContext(Dispatchers.IO) {
        if (photo == null || !photo.exists()) return@withContext null
        val dest = WeightPhotos.fileFor(app, uuid)
        val ok = WeightPhotos.compress(photo, dest)
        photo.delete()
        if (ok) dest.path else null
    }
    val profile = app.prefs.profile.value
    app.db.weights().insert(WeightEntity(uuid = uuid, measuredAt = now, weightKg = kg, ftp = profile.ftp, photoPath = photoPath))
    // saveProfile já pede a sincronização (sobe o perfil e a pesagem).
    app.prefs.saveProfile(profile.copy(weightKg = kg))
}

private suspend fun deleteWeighIn(app: GpsCiclismoApp, w: WeightEntity) {
    withContext(Dispatchers.IO) { w.photoPath?.let { File(it).delete() } }
    app.db.weights().delete(w.id)
    app.cloud.forgetWeight(w.uuid)
    // Apagou a mais recente: o perfil volta para a anterior.
    val latest = app.db.weights().latest()
    if (latest != null && latest.measuredAt < w.measuredAt) {
        app.prefs.saveProfile(app.prefs.profile.value.copy(weightKg = latest.weightKg))
    }
}

/**
 * Registrar o peso: digitar o número que a balança mostra e, se quiser, anexar a foto do visor
 * (câmera no celular; no tablet sem câmera, pela galeria ou só digitando).
 */
@Composable
fun WeighInDialog(app: GpsCiclismoApp, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val capture = remember { WeightPhotos.captureFile(context) }
    var text by remember { mutableStateOf("") }
    var photoVersion by remember { mutableIntStateOf(0) }
    var hasPhoto by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val lastFlow = remember { app.db.weights().latestFlow() }
    val last by lastFlow.collectAsStateWithLifecycle(null)
    val ftp = app.prefs.profile.value.ftp
    val kg = WeightLog.parse(text)

    LaunchedEffect(Unit) { capture.delete() }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        hasPhoto = ok && capture.exists() && capture.length() > 0
        photoVersion++
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            hasPhoto = withContext(Dispatchers.IO) { WeightPhotos.copyFromUri(context, uri) }
            if (!hasPhoto) error = "Não consegui abrir essa foto."
            photoVersion++
        }
    }
    val preview by produceState<Bitmap?>(null, photoVersion, hasPhoto) {
        value = if (hasPhoto) withContext(Dispatchers.IO) { WeightPhotos.load(capture.path, 600) } else null
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("Registrar peso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    last?.let { "Último: ${fmt1(it.weightKg)} kg em ${shortFmt.format(Date(it.measuredAt))}" }
                        ?: "Primeira pesagem. Sobe na balança e anota o número.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumField("Peso (kg)", text, Modifier.fillMaxWidth(), decimal = true) { text = it; error = null }
                if (kg != null) WeightLog.wattsPerKg(ftp, kg)?.let {
                    Text("FTP $ftp W → ${fmt1(it)} W/kg", color = Accent, style = MaterialTheme.typography.bodyMedium)
                }
                preview?.let { bmp ->
                    Image(
                        bmp.asImageBitmap(), "Foto da balança",
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCamera) OutlinedButton(onClick = {
                        error = null
                        runCatching {
                            capture.delete()
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", capture)
                            camera.launch(uri)
                        }.onFailure { error = if (it is ActivityNotFoundException) "Este aparelho não tem app de câmera." else "Não deu para abrir a câmera." }
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                        Text(if (hasPhoto) " Outra foto" else " Foto", maxLines = 1)
                    }
                    OutlinedButton(onClick = {
                        error = null
                        runCatching { gallery.launch("image/*") }.onFailure { error = "Não deu para abrir a galeria." }
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Image, null, Modifier.size(18.dp))
                        Text(" Galeria", maxLines = 1)
                    }
                }
                Text(
                    if (hasCamera) "A foto do visor é opcional, mas ajuda a manter o hábito."
                    else "Sem câmera aqui: digite o peso, ou registre com foto pelo celular (aparece aqui pela nuvem).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (text.isNotBlank() && kg == null) Text("Peso entre 30 e 250 kg.", color = Red, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = kg != null && !saving, onClick = {
                saving = true
                scope.launch {
                    saveWeighIn(app, kg!!, if (hasPhoto) capture else null)
                    onDone()
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !saving) { Text("Agora não") } },
    )
}

/** Aviso no início do treino: já tem peso de hoje? (Só lembra, nunca impede de treinar.) */
@Composable
fun TodayWeightNote(app: GpsCiclismoApp, onRegister: () -> Unit) {
    LaunchedEffect(Unit) { app.cloud.requestSync() } // traz a pesagem feita no celular
    val lastFlow = remember { app.db.weights().latestFlow() }
    val last by lastFlow.collectAsStateWithLifecycle(null)
    val cloud by app.cloud.state.collectAsStateWithLifecycle()
    val today = last?.takeIf { WeightLog.sameDay(it.measuredAt, System.currentTimeMillis()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                today != null -> "✓ Peso de hoje: ${fmt1(today.weightKg)} kg"
                cloud.syncing -> "Procurando o peso de hoje na nuvem…"
                else -> "Você ainda não se pesou hoje."
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (today != null) Green else Amber,
        )
        if (today == null) TextButton(onClick = onRegister) { Text("Pesar") }
    }
}

@Composable
fun WeightHistoryScreen(app: GpsCiclismoApp, nav: NavController) {
    val flow = remember { app.db.weights().weights() }
    val weights by flow.collectAsStateWithLifecycle(emptyList())
    val profile by app.prefs.profile.collectAsStateWithLifecycle()
    val cloud by app.cloud.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<WeightEntity?>(null) }
    var deleting by remember { mutableStateOf<WeightEntity?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.cloud.requestSync() }

    Column(Modifier.fillMaxSize()) {
        BackBar("Peso", { nav.popBackStack() }) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Registrar peso") }
        }
        if (app.cloud.signedIn && cloud.syncing) Text(
            "Sincronizando com a nuvem…",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(onClick = { adding = true }, Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.large) {
                    Text("Registrar peso de hoje", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (weights.isEmpty()) item {
                Text(
                    "Nenhuma pesagem ainda. Pesa antes de treinar e tira foto do visor: no celular, pela câmera; " +
                        "no tablet, aparece pela nuvem.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                item { WeightSummary(weights, profile.ftp) }
                if (weights.size >= 2) item { WeightChart(weights) }
            }
            items(weights, key = { it.id }) { w ->
                val idx = weights.indexOf(w)
                val prev = weights.getOrNull(idx + 1)
                WeightRow(w, prev, onOpen = { viewing = w }, onDelete = { deleting = w })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (adding) WeighInDialog(app) { adding = false }
    viewing?.let { w ->
        val bmp by produceState<Bitmap?>(null, w.id) { value = withContext(Dispatchers.IO) { WeightPhotos.load(w.photoPath, 1400) } }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("${fmt1(w.weightKg)} kg · ${dayFmt.format(Date(w.measuredAt))}") },
            text = {
                bmp?.let { Image(it.asImageBitmap(), "Foto da balança", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                    ?: Text(if (w.photoPath == null) "Sem foto." else "Abrindo a foto…")
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Fechar") } },
        )
    }
    deleting?.let { w ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar pesagem?") },
            text = { Text("${fmt1(w.weightKg)} kg de ${dayFmt.format(Date(w.measuredAt))}. Some também da nuvem.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; scope.launch { deleteWeighIn(app, w) } }) { Text("Apagar", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun WeightSummary(weights: List<WeightEntity>, ftp: Int) {
    val now = System.currentTimeMillis()
    val latest = weights.first()
    val entries = weights.map { it.measuredAt to it.weightKg }
    fun delta(days: Int) = WeightLog.change(entries, days, now)?.let { (if (it > 0) "+" else "") + fmt1(it) + " kg" } ?: "--"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("Atual", fmt1(latest.weightKg), "kg", Modifier.weight(1f))
        Metric("W/kg", WeightLog.wattsPerKg(ftp, latest.weightKg)?.let { fmt1(it) } ?: "--", "", Modifier.weight(1f))
        Metric("7 dias", delta(7), "", Modifier.weight(1f))
        Metric("30 dias", delta(30), "", Modifier.weight(1f))
    }
}

/** Linha simples dos últimos 90 dias (desenhada à mão, sem biblioteca, para ficar leve no tablet). */
@Composable
private fun WeightChart(weights: List<WeightEntity>) {
    val since = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
    val pts = weights.filter { it.measuredAt >= since }.sortedBy { it.measuredAt }.ifEmpty { weights.take(2).reversed() }
    if (pts.size < 2) return
    val minKg = pts.minOf { it.weightKg }
    val maxKg = pts.maxOf { it.weightKg }
    val span = (maxKg - minKg).coerceAtLeast(1.0)
    val lo = minKg - span * 0.15
    val hi = maxKg + span * 0.15
    val t0 = pts.first().measuredAt
    val t1 = pts.last().measuredAt.coerceAtLeast(t0 + 1)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(colors = cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text("Últimos 90 dias", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${fmt1(minKg)}–${fmt1(maxKg)} kg", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0)) * size.width
                fun y(kg: Double) = size.height - ((kg - lo) / (hi - lo)).toFloat() * size.height
                for (i in 0..3) {
                    val gy = size.height * i / 3f
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                }
                val path = Path()
                pts.forEachIndexed { i, w ->
                    if (i == 0) path.moveTo(x(w.measuredAt), y(w.weightKg)) else path.lineTo(x(w.measuredAt), y(w.weightKg))
                }
                drawPath(path, line, style = Stroke(width = 5f))
                pts.forEach { drawCircle(line, radius = 6f, center = Offset(x(it.measuredAt), y(it.weightKg))) }
            }
            Row {
                Text(shortFmt.format(Date(t0)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shortFmt.format(Date(t1)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeightRow(w: WeightEntity, prev: WeightEntity?, onOpen: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, w.photoPath) {
        value = withContext(Dispatchers.IO) { WeightPhotos.load(w.photoPath, 160) }
    }
    Card(colors = cardColors(), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${fmt1(w.weightKg)} kg", style = MaterialTheme.typography.titleMedium.merge(Tabular))
                    prev?.let {
                        val d = Math.round((w.weightKg - it.weightKg) * 10) / 10.0
                        if (d != 0.0) Text(
                            "  " + (if (d > 0) "+" else "") + fmt1(d),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d > 0) Amber else Green,
                        )
                    }
                }
                Text(
                    dayFmt.format(Date(w.measuredAt)) +
                        (WeightLog.wattsPerKg(w.ftp, w.weightKg)?.let { " · ${fmt1(it)} W/kg" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Apagar", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
