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
import androidx.compose.material3.OutlinedButton
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

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("GPS Ciclismo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            buildString {
                if (profile.name.isNotBlank()) append("${profile.name} · ")
                append("FTP ${profile.ftp} W")
                if (profile.weightKg > 0) append(" · ${fmt1(profile.ftp / profile.weightKg)} W/kg")
                append(" · ${sensors.size} sensor(es)")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { start(null) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Filled.PedalBike, null)
            Text("  Pedal livre", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { nav.navigate("workouts") }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Filled.FitnessCenter, null)
            Text("  Treino por zonas", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("Sensores", Icons.Filled.Bluetooth, Modifier.weight(1f)) { nav.navigate("sensors") }
            HomeTile("Histórico", Icons.Filled.History, Modifier.weight(1f)) { nav.navigate("history") }
            HomeTile("Perfil", Icons.Filled.Person, Modifier.weight(1f)) { nav.navigate("profile") }
        }
    }
}

@Composable
private fun HomeTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(32.dp))
            Spacer(Modifier.height(6.dp))
            Text(label)
        }
    }
}
