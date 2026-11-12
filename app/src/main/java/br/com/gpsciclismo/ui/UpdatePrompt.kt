package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import br.com.gpsciclismo.BuildConfig
import br.com.gpsciclismo.update.AppUpdater
import br.com.gpsciclismo.update.RemoteVersion
import kotlinx.coroutines.launch

/** Ao abrir o app, confere se há versão nova no GitHub e oferece atualizar. */
@Composable
fun UpdatePrompt(enabled: Boolean) {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<RemoteVersion?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    var canInstall by remember { mutableStateOf(updater.canInstall()) }

    LaunchedEffect(Unit) { remote = updater.check() }
    // Ao voltar da tela de permissão, reavalia.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = updater.canInstall() }

    val r = remote
    if (!enabled || r == null || dismissed) return

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissed = true },
        title = { Text("Nova versão disponível") },
        text = {
            Column {
                Text("Versão ${r.versionName} (você tem a ${BuildConfig.VERSION_NAME}).")
                if (r.notes.isNotBlank()) Text("Novidade: ${r.notes}")
                if (!canInstall) {
                    Spacer(Modifier.height(8.dp))
                    Text("Na primeira vez o Android pede para liberar \"instalar apps desconhecidos\" para este app. Libere e volte aqui.")
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = Red) }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                if (!canInstall) {
                    updater.openInstallPermissionSettings()
                    return@TextButton
                }
                downloading = true
                error = null
                scope.launch {
                    runCatching { updater.download { progress = it } }
                        .onSuccess { updater.install(it); dismissed = true }
                        .onFailure { error = "Não consegui baixar: ${it.message}" }
                    downloading = false
                }
            }) { Text(if (canInstall) "Atualizar" else "Liberar instalação") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = { dismissed = true }) { Text("Depois") }
        },
    )
}
