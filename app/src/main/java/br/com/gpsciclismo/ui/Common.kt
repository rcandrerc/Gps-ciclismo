package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val Green = Color(0xFF2ECC71)
val Amber = Color(0xFFF5B041)
val Red = Color(0xFFE74C3C)
val Blue = Color(0xFF5DADE2)

private val colors = darkColorScheme(
    primary = Color(0xFF34C3A0),
    onPrimary = Color(0xFF00201A),
    secondary = Color(0xFF8BD3C1),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C2329),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        },
        actions = { actions() },
    )
}

@Composable
fun Metric(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, big: Boolean = false, color: Color = Color.Unspecified) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = if (big) 44.sp else 28.sp, fontWeight = FontWeight.Bold, color = color)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val br = Locale("pt", "BR")

fun fmtTime(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) String.format(br, "%d:%02d:%02d", h, m, s) else String.format(br, "%02d:%02d", m, s)
}

fun fmt1(v: Double): String = String.format(br, "%.1f", v)
fun fmtKm(m: Double): String = String.format(br, "%.2f", m / 1000)
fun fmtOpt(v: Int?): String = v?.toString() ?: "--"
