package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.gpsciclismo.data.CloudSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val syncFmt = SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR"))

/** Conta na nuvem: entrar com Google e ver a sincronização dos treinos entre aparelhos. */
@Composable
fun CloudAccountCard(cloud: CloudSync) {
    val state by cloud.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conta na nuvem", style = MaterialTheme.typography.titleMedium)
            val hint = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                !cloud.configured -> Text(
                    "Este APK ainda não tem a configuração do Firebase. Os treinos ficam só neste aparelho.",
                    style = MaterialTheme.typography.bodySmall, color = hint,
                )

                state.email != null -> {
                    Text("Conectado como ${state.email}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            state.syncing -> "Sincronizando…"
                            state.lastSync > 0 -> "Última sincronização: ${syncFmt.format(Date(state.lastSync))}"
                            else -> "Ainda não sincronizou."
                        },
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.error) Red else hint)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { cloud.requestSync() }, enabled = !state.syncing, modifier = Modifier.weight(1f)) {
                            Text("Sincronizar agora")
                        }
                        OutlinedButton(onClick = { cloud.signOut() }, modifier = Modifier.weight(1f)) { Text("Sair") }
                    }
                    Text(
                        "Os treinos ficam salvos no aparelho e sobem sozinhos quando há internet. " +
                            "Entre com a mesma conta Google no celular e no tablet para ver tudo nos dois.",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                }

                else -> {
                    Text(
                        "Entre com sua conta Google para guardar os treinos e o perfil na nuvem e ver tudo no celular e no tablet. " +
                            "Os treinos que já estão aqui sobem junto.",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                    Button(
                        onClick = {
                            val ok = runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(cloud.googleLoginUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.isSuccess
                            opened = ok
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Entrar com Google") }
                    if (opened) Text(
                        "Abriu o navegador: escolha a conta Google e toque em Voltar para o app.",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.error) Red else hint)
                    }
                }
            }
        }
    }
}
