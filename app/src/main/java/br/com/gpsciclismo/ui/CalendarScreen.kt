package br.com.gpsciclismo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CalendarDay
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.TrainingCalendar
import br.com.gpsciclismo.core.WorkoutPlan
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ptBR = Locale("pt", "BR")
private val dayTitle = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ptBR)

@Composable
fun CalendarScreen(app: GpsCiclismoApp, nav: NavController) {
    val calendar by app.calendar.calendar.collectAsStateWithLifecycle()
    val profile by app.prefs.profile.collectAsStateWithLifecycle()
    val start = rememberStartRide(app, nav)
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    var loading by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        offline = !app.calendar.refresh()
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        BackBar(calendar?.name ?: "Calendário", { nav.popBackStack() }) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Filled.Refresh, "Atualizar plano") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (offline) item {
                Text(
                    if (calendar == null) "Não consegui baixar o plano. Confira a internet e toque em atualizar."
                    else "Sem internet: mostrando a última versão salva do plano.",
                    color = Amber, style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                MonthGrid(month, today, selected, calendar,
                    onPrev = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) },
                    onSelect = { selected = it })
            }
            item {
                DayDetail(selected, calendar?.day(selected), profile.ftp) { plan -> start(plan) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    calendar: TrainingCalendar?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mês anterior") }
            Text(
                month.month.getDisplayName(TextStyle.FULL, ptBR).replaceFirstChar { it.titlecase(ptBR) } + " ${month.year}",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próximo mês") }
        }
        // Semana começa na segunda, como a planilha de treinos.
        Row {
            listOf("S", "T", "Q", "Q", "S", "S", "D").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells = offset + month.lengthOfMonth()
        val weeks = (cells + 6) / 7
        for (w in 0 until weeks) {
            Row {
                for (d in 0 until 7) {
                    val idx = w * 7 + d - offset
                    if (idx < 0 || idx >= month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(idx + 1)
                        DayCell(date, calendar?.day(date), date == today, date == selected, Modifier.weight(1f)) { onSelect(date) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Legend(MaterialTheme.colorScheme.primary, "Treino")
            Legend(Gray, "Descanso")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("  $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayCell(date: LocalDate, day: CalendarDay?, isToday: Boolean, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.aspectRatio(1f).padding(2.dp).clip(shape)
            .background(if (isSelected) primary.copy(alpha = 0.25f) else Color.Transparent)
            .then(if (isToday) Modifier.border(1.5.dp, primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            val dot = when {
                day == null -> Color.Transparent
                day.isRest -> Gray
                else -> primary
            }
            Box(Modifier.padding(top = 2.dp).size(6.dp).clip(CircleShape).background(dot))
        }
    }
}

@Composable
private fun DayDetail(date: LocalDate, day: CalendarDay?, ftp: Int, onStart: (WorkoutPlan) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(date.format(dayTitle).replaceFirstChar { it.titlecase(ptBR) },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day == null) {
                Text("Nada planejado para este dia.", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            Text(day.displayTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val plan = day.workout
            if (plan != null) {
                Text(
                    buildString {
                        append(fmtTime(plan.totalSec)); append(" · ${plan.blocks.size} blocos")
                        when (day.where.lowercase()) { "rolo" -> append(" · rolo"); "rua" -> append(" · rua") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (day.notes.isNotBlank()) Text(day.notes)
            if (plan != null) {
                Spacer(Modifier.height(4.dp))
                plan.blocks.forEachIndexed { i, b ->
                    Row {
                        Text("${i + 1}.", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmtTime(b.durationSec), Modifier.weight(0.35f))
                        Text(targetText(b.target, ftp), Modifier.weight(0.65f))
                    }
                }
                Button(onClick = { onStart(plan) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("  Iniciar este treino", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun targetText(t: PowerTarget, ftp: Int): String = when (t) {
    is PowerTarget.Zone -> t.range(ftp, 0)?.let { "Z${t.zone} (${it.first}–${it.last} W)" } ?: "Z${t.zone}"
    is PowerTarget.Watts -> "${t.watts} W"
    PowerTarget.Free -> "Livre"
}
