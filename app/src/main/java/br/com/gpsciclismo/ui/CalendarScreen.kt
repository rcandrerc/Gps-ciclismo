package br.com.gpsciclismo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CalendarDay
import br.com.gpsciclismo.core.PowerTarget
import br.com.gpsciclismo.core.TrainingCalendar
import br.com.gpsciclismo.core.WorkoutPlan
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.Instant
import br.com.gpsciclismo.core.WorkoutScore
import br.com.gpsciclismo.data.RideEntity
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Timelapse
import br.com.gpsciclismo.core.DayStatus
import br.com.gpsciclismo.core.DoneRide
import br.com.gpsciclismo.core.PlanCompliance
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ptBR = Locale("pt", "BR")
private val hourFmt = DateTimeFormatter.ofPattern("HH:mm", ptBR)
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

    val listState = rememberLazyListState()
    // Treinos salvos no histórico, agrupados pelo dia em que começaram.
    val ridesFlow = remember { app.db.rides().rides() }
    val rides by ridesFlow.collectAsStateWithLifecycle(emptyList())
    val ridesByDay = remember(rides) {
        rides.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val doneByDay = remember(ridesByDay) { ridesByDay.mapValues { (_, l) -> l.map { it.toDoneRide() } } }
    val statusOf: (LocalDate) -> DayStatus = { date ->
        PlanCompliance.status(calendar?.day(date), doneByDay[date].orEmpty(), date, today)
    }
    val todayPlan = calendar?.day(today)?.workout?.takeIf { ridesByDay[today].isNullOrEmpty() }

    Column(Modifier.fillMaxSize()) {
        BackBar(calendar?.name ?: "Calendário", { nav.popBackStack() }) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Filled.Refresh, "Atualizar plano") }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Tablet deitado (ou tela larga e baixa): calendário à esquerda, treino do dia à direita,
            // para o botão "Iniciar" nunca ficar escondido embaixo do mês.
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
            val status: @Composable () -> Unit = {
                if (offline) Text(
                    if (calendar == null) "Não consegui baixar o plano. Confira a internet e toque em atualizar."
                    else "Sem internet: mostrando a última versão salva do plano.",
                    color = Amber, style = MaterialTheme.typography.bodySmall,
                )
            }
            val todayButton: @Composable () -> Unit = {
                // Atalho: o treino de hoje sempre a um toque, sem precisar achar o dia no mês.
                if (todayPlan != null) {
                    Button(onClick = { start(todayPlan) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text("  Treino de hoje: ${todayPlan.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val grid: @Composable (Dp) -> Unit = { cellHeight ->
                MonthGrid(month, today, selected, statusOf, cellHeight,
                    onPrev = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) },
                    onSelect = { selected = it })
            }
            val detail: @Composable () -> Unit = {
                DayDetail(selected, calendar?.day(selected), statusOf(selected), ridesByDay[selected].orEmpty(), profile.ftp,
                    onOpenRide = { nav.navigate("ride/$it") }) { plan -> start(plan) }
            }
            if (sideBySide) {
                // Altura da célula calculada para o mês inteiro (até 6 semanas) caber sem rolar.
                val cell = ((maxHeight - 140.dp) / 6).coerceIn(32.dp, 56.dp)
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        status()
                        grid(cell)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { todayButton() }
                        item { detail() }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { status() }
                    item { todayButton() }
                    item { grid(46.dp) }
                    item { detail() }
                    item { Spacer(Modifier.height(16.dp)) }
                }
                // Ao tocar num dia, rola até o treino dele (antes ficava escondido embaixo do mês).
                var first by remember { mutableStateOf(true) }
                LaunchedEffect(selected) {
                    if (first) first = false else listState.animateScrollToItem(3)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    statusOf: (LocalDate) -> DayStatus,
    cellHeight: Dp,
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
                        Spacer(Modifier.weight(1f).height(cellHeight))
                    } else {
                        val date = month.atDay(idx + 1)
                        DayCell(date, statusOf(date), date == today, date == selected, Modifier.weight(1f).height(cellHeight)) { onSelect(date) }
                    }
                }
            }
        }
        // Legenda em duas linhas para caber no celular em pé.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
            LegendIcon(Icons.Filled.CheckCircle, Green, "Feito")
            LegendIcon(Icons.Filled.Timelapse, Amber, "Em parte")
            LegendIcon(Icons.Filled.Cancel, Red, "Furou")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(MaterialTheme.colorScheme.primary, "A fazer")
            Legend(Gray, "Descanso")
            Legend(Green, "Pedal extra")
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
private fun LegendIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Cor de fundo do dia pelo resultado: dá para ver a semana inteira de longe. */
private fun statusTint(s: DayStatus): Color = when (s) {
    DayStatus.DONE -> Green.copy(alpha = 0.16f)
    DayStatus.PARTIAL -> Amber.copy(alpha = 0.16f)
    DayStatus.MISSED -> Red.copy(alpha = 0.14f)
    else -> Color.Transparent
}

@Composable
private fun DayCell(date: LocalDate, status: DayStatus, isToday: Boolean, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.padding(2.dp).clip(shape)
            .background(if (isSelected) primary.copy(alpha = 0.25f) else statusTint(status))
            .then(if (isToday) Modifier.border(1.5.dp, primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            when (status) {
                DayStatus.DONE -> Icon(Icons.Filled.CheckCircle, "Feito", Modifier.size(12.dp), tint = Green)
                DayStatus.PARTIAL -> Icon(Icons.Filled.Timelapse, "Feito em parte", Modifier.size(12.dp), tint = Amber)
                DayStatus.MISSED -> Icon(Icons.Filled.Cancel, "Não feito", Modifier.size(12.dp), tint = Red)
                else -> {
                    val dot = when (status) {
                        DayStatus.PLANNED -> primary
                        DayStatus.REST -> Gray
                        DayStatus.EXTRA -> Green
                        else -> Color.Transparent
                    }
                    Box(Modifier.padding(top = 3.dp, bottom = 3.dp).size(6.dp).clip(CircleShape).background(dot))
                }
            }
        }
    }
}

@Composable
private fun DayDetail(
    date: LocalDate,
    day: CalendarDay?,
    status: DayStatus,
    done: List<RideEntity>,
    ftp: Int,
    onOpenRide: (Long) -> Unit,
    onStart: (WorkoutPlan) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            DayStatus.PARTIAL -> StatusNote(Icons.Filled.Timelapse, Amber, "Feito em parte: menos tempo ou aproveitamento baixo.")
            DayStatus.MISSED -> StatusNote(Icons.Filled.Cancel, Red, "Este treino não foi feito.")
            else -> {}
        }
        done.forEach { r -> DoneRideCard(r) { onOpenRide(r.id) } }
        PlanCard(date, day, done.isNotEmpty(), ftp, onStart)
    }
}

/** Treino já feito neste dia: resumo e toque para ver tudo (mapa, gráficos, blocos). */
@Composable
private fun DoneRideCard(r: RideEntity, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Green.copy(alpha = 0.12f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Green)
                Text("  Feito às ${hourFmt.format(Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.labelLarge, color = Green)
            }
            Text(r.workoutName ?: "Pedal livre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            r.adherencePct?.let { pct ->
                Text("${WorkoutScore.starsText(pct)}  $pct% de aproveitamento", color = Amber)
            }
            Text(
                buildString {
                    append(fmtTime(r.elapsedSec))
                    if (r.avgPower > 0) append(" · ${r.avgPower} W médios (máx ${r.maxPower})")
                    if (r.avgHr > 0) append(" · FC ${r.avgHr} bpm")
                    if (r.avgCadence > 0) append(" · ${r.avgCadence} rpm")
                    if (r.distanceM > 50) append(" · ${fmtKm(r.distanceM)} km")
                    if (r.kcal > 0) append(" · ${r.kcal} kcal")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Toque para ver os detalhes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PlanCard(date: LocalDate, day: CalendarDay?, alreadyDone: Boolean, ftp: Int, onStart: (WorkoutPlan) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(date.format(dayTitle).replaceFirstChar { it.titlecase(ptBR) },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day == null) {
                if (alreadyDone) return@Column
                Text("Nada planejado para este dia.", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            Text((if (alreadyDone) "Planejado: " else "") + day.displayTitle(),
                style = if (alreadyDone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
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
            if (plan != null && alreadyDone) {
                // Já feito: o plano fica só como referência; refazer é opção discreta.
                OutlinedButton(onClick = { onStart(plan) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Fazer de novo")
                }
            } else if (plan != null) {
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

@Composable
private fun StatusNote(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text("  $text", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Resumo do pedal para a conta de concluído/parcial. */
fun RideEntity.toDoneRide(): DoneRide = DoneRide(maxOf(elapsedSec, movingSec), adherencePct)
