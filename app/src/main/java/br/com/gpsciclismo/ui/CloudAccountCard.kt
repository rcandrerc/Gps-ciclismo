package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.gpsciclismo.data.CloudSync
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val syncFmt = SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR"))

/** Conta na nuvem: entrar/criar conta e ver a sincronização dos treinos entre aparelhos. */
@Composable
fun CloudAccountCard(cloud: CloudSync) {
    val state by cloud.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(state.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // texto, é erro?

    fun run(block: suspend () -> String?, ok: String? = null) {
        busy = true
        msg = null
        scope.launch {
            val err = block()
            busy = false
            msg = if (err != null) err to true else ok?.let { it to false }
        }
    }

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
                        OutlinedButton(onClick = { cloud.signOut(); password = "" }, modifier = Modifier.weight(1f)) { Text("Sair") }
                    }
                    Text(
                        "Os treinos ficam salvos no aparelho e sobem sozinhos quando há internet. " +
                            "Entre com a mesma conta no celular e no tablet para ver tudo nos dois.",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                }

                else -> {
                    Text(
                        "Entre para guardar os treinos e o perfil na nuvem e ver tudo no celular e no tablet. " +
                            "Os treinos que já estão aqui sobem junto.",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                    OutlinedTextField(
                        email, { email = it.trim() }, label = { Text("E-mail") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Senha (mín. 6)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val filled = email.contains('@') && password.length >= 6
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run({ cloud.signIn(email, password) }) }, enabled = filled && !busy,
                            modifier = Modifier.weight(1f)) { Text("Entrar") }
                        OutlinedButton(onClick = { run({ cloud.signUp(email, password) }) }, enabled = filled && !busy,
                            modifier = Modifier.weight(1f)) { Text("Criar conta") }
                    }
                    TextButton(
                        onClick = { run({ cloud.resetPassword(email) }, ok = "Mandamos um e-mail para trocar a senha.") },
                        enabled = email.contains('@') && !busy,
                    ) { Text("Esqueci a senha") }
                    if (busy) Text("Aguarde…", style = MaterialTheme.typography.bodySmall, color = hint)
                }
            }
            msg?.let { (text, isError) ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) Red else hint)
            }
        }
    }
}
