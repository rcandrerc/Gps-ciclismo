package br.com.gpsciclismo.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.WorkoutScore
import br.com.gpsciclismo.data.RideEntity
import br.com.gpsciclismo.data.RidePointEntity
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

@Composable
fun HistoryScreen(app: GpsCiclismoApp, nav: NavController) {
    val flow = remember { app.db.rides().rides() }
    val rides by flow.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        BackBar("Histórico", { nav.popBackStack() })
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rides.isEmpty()) item {
                Text("Nenhum treino salvo ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rides, key = { it.id }) { r ->
                Card(onClick = { nav.navigate("ride/${r.id}") }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(dateFmt.format(Date(r.startedAt)) + (r.workoutName?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall)
                        r.adherencePct?.let { pct ->
                            Text("${WorkoutScore.starsText(pct)}  $pct% de aproveitamento", color = Amber,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "${fmtKm(r.distanceM)} km · ${fmtTime(r.elapsedSec)} · ${fmt1(r.avgSpeedKmh)} km/h" +
                                (if (r.avgPower > 0) " · ${r.avgPower} W" else "") +
                                (if (r.avgHr > 0) " · ${r.avgHr} bpm" else ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RideDetailScreen(app: GpsCiclismoApp, id: Long, onBack: () -> Unit) {
    var ride by remember { mutableStateOf<RideEntity?>(null) }
    var points by remember { mutableStateOf<List<RidePointEntity>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val weight = app.prefs.profile.value.weightKg

    LaunchedEffect(id) {
        ride = app.db.rides().ride(id)
        points = app.db.rides().points(id)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(ride?.let { dateFmt.format(Date(it.startedAt)) } ?: "Treino", onBack) {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Excluir") }
        }
        val r = ride ?: return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val geo = points.mapNotNull { p -> if (p.lat != null && p.lon != null) GeoPoint(p.lat, p.lon) else null }
            if (geo.size >= 2) RouteMap(geo, Modifier.fillMaxWidth().height(320.dp))
            else Text("Sem percurso de GPS neste treino.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            r.workoutName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            r.adherencePct?.let { pct -> ScoreCard(pct) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Distância", fmtKm(r.distanceM), "km", Modifier.weight(1f))
                Metric("Tempo", fmtTime(r.elapsedSec), "em movimento ${fmtTime(r.movingSec)}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Vel. média", fmt1(r.avgSpeedKmh), "km/h", Modifier.weight(1f))
                Metric("Vel. máxima", fmt1(r.maxSpeedKmh), "km/h", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Pot. média", r.avgPower.toString(),
                    if (weight > 0 && r.avgPower > 0) "W · ${fmt1(r.avgPower / weight)} W/kg" else "W", Modifier.weight(1f))
                Metric("Pot. máxima", r.maxPower.toString(), "W", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("FC média", r.avgHr.toString(), "máx ${r.maxHr} bpm", Modifier.weight(1f))
                Metric("Cadência", r.avgCadence.toString(), "rpm", Modifier.weight(1f))
            }
            Metric("Calorias", r.kcal.toString(), "kcal (aprox.)", Modifier.fillMaxWidth())
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Excluir este treino?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { app.db.rides().delete(id); onBack() }
            }) { Text("Excluir") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
    )
}

@Composable
fun RouteMap(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        m.overlays += Polyline(m).apply {
            setPoints(points)
            outlinePaint.color = AColor.rgb(231, 76, 60)
            outlinePaint.strokeWidth = 10f
        }
        val box = BoundingBox.fromGeoPoints(points)
        m.post { m.zoomToBoundingBox(box.increaseByScale(1.2f), false) }
        m.invalidate()
    })
}

/** Nota do treino por blocos: estrelas e % do tempo na zona. */
@Composable
fun ScoreCard(pct: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AVALIAÇÃO DO TREINO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(WorkoutScore.starsText(pct), fontSize = 36.sp, color = Amber)
            Text("$pct% de aproveitamento", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tempo dentro da zona/alvo de cada bloco (sem contar pausas, blocos livres e os primeiros segundos de cada bloco).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
