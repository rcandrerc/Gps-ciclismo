package br.com.gpsciclismo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
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
import br.com.gpsciclismo.core.PowerCoach
import java.util.Locale

// Paleta: grafite azulado com acento verde-água. Cores de estado com bom contraste no escuro.
val Green = Color(0xFF3DDC97)
val Amber = Color(0xFFFFC266)
val Red = Color(0xFFFF5C5C)
val Blue = Color(0xFF6CB8FF)
val Gray = Color(0xFF9AA3AD)
val Accent = Color(0xFF3FE0C5)

/** Cor da potência em relação ao alvo do bloco: verde na zona, cinza abaixo, vermelho acima. */
fun powerStatusColor(status: PowerCoach.Status): Color = when (status) {
    PowerCoach.Status.IN_ZONE -> Green
    PowerCoach.Status.BELOW -> Gray
    PowerCoach.Status.ABOVE -> Red
    else -> Color.Unspecified
}

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00211C),
    primaryContainer = Color(0xFF0F3B36),
    onPrimaryContainer = Color(0xFFB4F5EA),
    secondary = Color(0xFF9FC9C1),
    secondaryContainer = Color(0xFF213431),
    onSecondaryContainer = Color(0xFFD5ECE7),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EBF0),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE6EBF0),
    surfaceVariant = Color(0xFF1A222B),
    onSurfaceVariant = Color(0xFF94A0AC),
    surfaceContainerLowest = Color(0xFF080B0F),
    surfaceContainerLow = Color(0xFF11171E),
    surfaceContainer = Color(0xFF141B23),
    surfaceContainerHigh = Color(0xFF1A222B),
    surfaceContainerHighest = Color(0xFF212A34),
    outline = Color(0xFF3A4652),
    outlineVariant = Color(0xFF26303A),
    error = Red,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Números com largura fixa: o valor não "dança" quando muda a cada segundo. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val typography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(letterSpacing = 0.8.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography, content = content)

/** Centraliza o conteúdo e limita a largura em tablet, para não esticar formulários e listas. */
@Composable
fun Centered(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}

/** Cartão discreto padrão do app (sem sombra, só a cor da superfície e uma borda fina). */
@Composable
fun cardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        },
        actions = { actions() },
        windowInsets = WindowInsets(0, 0, 0, 0), // as barras do sistema já são tratadas na raiz
    )
}

@Composable
fun Metric(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, big: Boolean = false, color: Color = Color.Unspecified) {
    Card(modifier, colors = cardColors()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = if (big) 44.sp else 28.sp, fontWeight = FontWeight.Bold, color = color,
                style = Tabular, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ptBr = Locale("pt", "BR")

fun fmtTime(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) String.format(ptBr, "%d:%02d:%02d", h, m, s) else String.format(ptBr, "%02d:%02d", m, s)
}

fun fmt1(v: Double): String = String.format(ptBr, "%.1f", v)
fun fmtKm(m: Double): String = String.format(ptBr, "%.2f", m / 1000)
fun fmtOpt(v: Int?): String = v?.toString() ?: "--"
