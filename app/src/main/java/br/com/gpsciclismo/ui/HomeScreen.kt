package br.com.gpsciclismo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.ble.BleSensorManager
import br.com.gpsciclismo.core.WorkoutPlan

/** Todas as permissões que o treino precisa. */
fun ridePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    addAll(BleSensorManager.requiredPermissions())
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.distinct().toTypedArray()

/** Pede as permissões e, se o GPS foi liberado, inicia o treino. */
@Composable
fun rememberStartRide(app: GpsCiclismoApp, nav: NavController): (WorkoutPlan?) -> Unit {
    val pending = remember { arrayOfNulls<WorkoutPlan>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            app.recorder.start(pending[0])
            nav.navigate("live") { popUpTo("home") }
        }
    }
    return { plan ->
        pending[0] = plan
        launcher.launch(ridePermissions())
    }
}

@Composable
fun HomeScreen(app: GpsCiclismoApp, nav: NavController) {
    val profile by app.prefs.profile.collectAsStateWithLifecycle()
    val sensors by app.prefs.sensors.collectAsStateWithLifecycle()
    val start = rememberStartRide(app, nav)
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("GPS CICLISMO", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        Text(
            if (profile.name.isNotBlank()) "Bora pedalar, ${profile.name.trim().substringBefore(' ')}" else "Bora pedalar",
            style = MaterialTheme.typography.headlineMedium,
        )

        // Cartão do atleta: um degradê leve (desenhado uma vez, não pesa).
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainer)))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatBlock("FTP", "${profile.ftp}", "W")
                StatBlock("W/kg", if (profile.weightKg > 0) fmt1(profile.ftp / profile.weightKg) else "--", "")
                StatBlock("Sensores", "${sensors.size}", "")
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { start(null) },
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Filled.PedalBike, null)
            Text("  Pedal livre", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = { nav.navigate("workouts") },
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Filled.FitnessCenter, null)
            Text("  Treino por zonas", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("Sensores", Icons.Filled.Bluetooth, Modifier.weight(1f)) { nav.navigate("sensors") }
            HomeTile("Histórico", Icons.Filled.History, Modifier.weight(1f)) { nav.navigate("history") }
            HomeTile("Perfil", Icons.Filled.Person, Modifier.weight(1f)) { nav.navigate("profile") }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, unit: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = Tabular)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun HomeTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = cardColors()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
