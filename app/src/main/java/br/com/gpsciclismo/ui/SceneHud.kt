package br.com.gpsciclismo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.PowerCoach
import br.com.gpsciclismo.core.SceneTrack
import br.com.gpsciclismo.recording.LiveRide
import br.com.gpsciclismo.recording.RecStatus
import java.util.Locale

private val GlassBg = Color(0x94090D18)
private val GlassLine = Color(0x17FFFFFF)
private val Muted = Color(0xFF9AA6BD)
private val Ink = Color(0xFFEEF1F6)

/**
 * Tela de treino com a cena 2D ocupando tudo e os dados por cima (tablet deitado).
 * Os tamanhos seguem a largura da tela, como na prévia aprovada.
 */
@Composable
fun SceneRideView(
    app: GpsCiclismoApp,
    live: LiveRide,
    ftp: Int,
    track: SceneTrack,
    onShowPanel: () -> Unit,
    onStop: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = maxWidth / 100f   // 1% da largura
        val density = LocalDensity.current
        fun ts(f: Float): TextUnit = with(density) { (u * f).toSp() }

        RideScene(live, track, Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().padding(u * 2.2f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(u * 1.6f), verticalAlignment = Alignment.Top) {
                PowerCard(live, ftp, track, u, ::ts, Modifier.weight(1.05f))
                BlockCard(live, track, u, ::ts, Modifier.weight(1.1f))
                StatsCard(live, track, u, ::ts, Modifier.weight(0.95f))
            }
            Spacer(Modifier.weight(1f))
            if (track.blockCount > 0) WorkoutStrip(live, track, u, Modifier.fillMaxWidth().height(max(40.dp, u * 4.2f)))
            Spacer(Modifier.height(u * 1f))
            // Barra de controles com nome em cada botão, bem visível em cima da cena.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 0.9f), verticalAlignment = Alignment.CenterVertically) {
                val h = max(56.dp, u * 5.6f)
                val fs = ts(2.1f)
                val coachOn = live.workout != null && live.coach != null && live.coach.status != PowerCoach.Status.FINISHED
                val paused = live.status == RecStatus.PAUSED
                if (coachOn) PillBtn("−10 W", h, fs, Modifier.weight(1f)) { app.recorder.adjustIntensity(-10) }
                PillBtn(
                    if (paused) "Continuar" else "Pausar", h, fs, Modifier.weight(1.4f),
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    bg = if (paused) Amber else Color(0xFF2EC4B6), fg = Color(0xFF07211E),
                ) { if (paused) app.recorder.resume() else app.recorder.pause() }
                if (coachOn) PillBtn("+10 W", h, fs, Modifier.weight(1f)) { app.recorder.adjustIntensity(10) }
                if (live.setup.indoor) PillBtn(
                    if (live.ergOn) "ERG ligado" else "ERG desligado", h, fs, Modifier.weight(1.3f),
                    fg = if (live.ergOn) Green else Ink,
                ) { app.recorder.toggleErg() }
                PillBtn("Painel", h, fs, Modifier.weight(1.1f), icon = Icons.Filled.Dashboard, onClick = onShowPanel)
                PillBtn("Finalizar", h, fs, Modifier.weight(1.3f), icon = Icons.Filled.Stop, bg = Red, fg = Color.White, onClick = onStop)
            }
        }
    }
}

@Composable
private fun PillBtn(
    text: String,
    height: Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    bg: Color = Color(0xE6202A40),
    fg: Color = Ink,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(height).clip(shape).background(bg).border(1.dp, Color(0x40FFFFFF), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(height * 0.42f))
        Text(text, color = fg, fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = fontSize, maxLines = 1)
    }
}

/** No celular em pé: a cena num quadro no topo da tela, com o tempo que falta do bloco. */
@Composable
fun SceneBanner(live: LiveRide, track: SceneTrack, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp))) {
        val u = maxWidth / 100f
        val density = LocalDensity.current
        RideScene(live, track, Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
            live.coach?.takeIf { it.status != PowerCoach.Status.FINISHED && live.workout != null }?.let { c ->
                Glass(u) { Text("faltam ${fmtClock(c.blockRemainingSec)}", color = Ink, fontFamily = Condensed,
                    fontWeight = FontWeight.Bold, fontSize = with(density) { (u * 4.5f).toSp() }) }
            }
        }
    }
}

@Composable
private fun Glass(u: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(u * 1.6f)
    Box(
        modifier.background(GlassBg, shape).border(1.dp, GlassLine, shape)
            .padding(horizontal = u * 1.6f, vertical = u * 1.2f),
    ) { content() }
}

@Composable
private fun Label(text: String, ts: (Float) -> TextUnit) {
    Text(text.uppercase(), color = Muted, fontSize = ts(1.15f), fontWeight = FontWeight.SemiBold,
        style = TextStyle(letterSpacing = ts(0.16f)), maxLines = 1)
}

@Composable
private fun PowerCard(live: LiveRide, ftp: Int, track: SceneTrack, u: Dp, ts: (Float) -> TextUnit, modifier: Modifier) {
    val coach = live.coach
    val activeRange = if (coach != null && coach.status != PowerCoach.Status.FINISHED) coach.range else null
    val shown = PowerCoach.compare(live.powerAvg, activeRange)
    val color = if (shown == PowerCoach.Status.NO_TARGET) Ink else powerStatusColor(shown)
    val zone = coach?.blockIndex?.let { track.zones.getOrNull(it) }?.takeIf { it > 0 }
        ?: live.powerAvg?.let { CogganZones.zoneFor(it, ftp) }?.takeIf { it > 0 }
    Glass(u, modifier) {
        Column {
            Label("Potência · média ${live.powerWindowSec}s", ts)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtOpt(live.powerAvg), color = color, fontFamily = Condensed, fontWeight = FontWeight.Bold,
                    fontSize = ts(9.4f), style = Tabular, maxLines = 1)
                Text(" W", color = Muted, fontFamily = Condensed, fontWeight = FontWeight.SemiBold,
                    fontSize = ts(3f), modifier = Modifier.padding(bottom = u * 1.2f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 0.7f)) {
                if (zone != null) Text(
                    "Z$zone · ${shortZoneName(zone)}", color = Color(0xFF0B0F19), fontFamily = Condensed,
                    fontWeight = FontWeight.Bold, fontSize = ts(1.7f), maxLines = 1,
                    modifier = Modifier.background(zoneColor(zone), CircleShape).padding(horizontal = u * 0.9f, vertical = u * 0.1f),
                )
                Text(
                    when (shown) {
                        PowerCoach.Status.IN_ZONE -> "na zona"
                        PowerCoach.Status.BELOW -> "abaixo do alvo"
                        PowerCoach.Status.ABOVE -> "acima do alvo"
                        else -> if (live.powerAvg == null) "sem sinal de potência" else ""
                    },
                    color = if (shown == PowerCoach.Status.NO_TARGET) Muted else color,
                    fontSize = ts(1.35f), fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BlockCard(live: LiveRide, track: SceneTrack, u: Dp, ts: (Float) -> TextUnit, modifier: Modifier) {
    val plan = live.workout
    val coach = live.coach
    val paused = live.status == RecStatus.PAUSED
    Glass(u, modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val idx = coach?.blockIndex ?: -1
            val block = plan?.blocks?.getOrNull(idx)
            val finished = coach?.status == PowerCoach.Status.FINISHED
            val title = when {
                paused -> "Pausado"
                plan == null -> "Pedal livre"
                finished -> "Treino concluído!"
                coach?.ftpTest == true -> "Teste de FTP"
                else -> "Bloco ${idx + 1} de ${plan.blocks.size}"
            }
            Text(title.uppercase(Locale("pt", "BR")), color = if (paused) Amber else if (finished) Green else Ink,
                fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = ts(2.6f), maxLines = 1,
                style = TextStyle(letterSpacing = ts(0.1f)))
            Row(verticalAlignment = Alignment.Bottom) {
                val big = if (block != null && coach != null && !finished) coach.blockRemainingSec else live.elapsedSec
                Text(if (block != null && !finished) "FALTAM " else "TEMPO ", color = Muted, fontFamily = Condensed,
                    fontWeight = FontWeight.SemiBold, fontSize = ts(1.9f), modifier = Modifier.padding(bottom = u * 0.8f))
                Text(fmtClock(big), color = Ink, fontFamily = Condensed, fontWeight = FontWeight.Bold,
                    fontSize = ts(5.6f), style = Tabular, maxLines = 1)
            }
            if (block != null && coach != null && !finished) {
                val prog = ((block.durationSec - coach.blockRemainingSec).toFloat() / block.durationSec.coerceAtLeast(1)).coerceIn(0f, 1f)
                val zc = zoneColor(track.zones.getOrElse(idx) { 0 })
                Canvas(Modifier.fillMaxWidth().padding(vertical = u * 0.7f).height(u * 0.7f)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Color(0x1FFFFFFF), cornerRadius = r)
                    drawRoundRect(zc, size = Size(size.width * prog, size.height), cornerRadius = r)
                }
                val next = plan?.blocks?.getOrNull(idx + 1)
                Text(
                    if (next != null) "Próximo: ${nextLabel(track, idx + 1)} · ${fmtClock(next.durationSec)}" else "Último bloco",
                    color = Muted, fontSize = ts(1.3f), maxLines = 1, textAlign = TextAlign.Center,
                )
            } else if (plan == null) {
                Text("A estrada sobe e desce para dar ritmo", color = Muted, fontSize = ts(1.3f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatsCard(live: LiveRide, track: SceneTrack, u: Dp, ts: (Float) -> TextUnit, modifier: Modifier) {
    val coach = live.coach?.takeIf { it.status != PowerCoach.Status.FINISHED }
    val rows = buildList {
        if (coach?.target != null) {
            val r = coach.range
            add(Triple("Alvo", coach.target.label().removeSuffix(" W"),
                when {
                    r == null -> ""
                    coach.ftpTest -> "W ou mais"
                    else -> "W · ${r.first}–${r.last}"
                }))
        }
        if (coach?.ftpTest == true && live.testAvgPower != null) add(Triple("Média teste", "${live.testAvgPower}", "W"))
        add(Triple("Cadência", fmtOpt(live.cadence), "rpm"))
        add(Triple("FC", fmtOpt(live.heartRate), "bpm"))
        val total = live.workout?.totalSec
        if (total != null) add(Triple("Fim em", fmtClock((total - live.elapsedSec).coerceAtLeast(0)), ""))
        else add(Triple("Distância", fmtKm(live.distanceM), "km"))
    }
    Glass(u, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(u * 0.2f)) {
            rows.forEachIndexed { i, (k, v, unit) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).padding(bottom = u * 0.5f)) { Label(k, ts) }
                    Text(v, color = if (i == 0 && coach?.target != null) Jersey2 else Ink, fontFamily = Condensed,
                        fontWeight = FontWeight.SemiBold, fontSize = ts(2.5f), style = Tabular, maxLines = 1)
                    if (unit.isNotEmpty()) Text(" $unit", color = Muted, fontFamily = Condensed, fontSize = ts(1.4f),
                        maxLines = 1, modifier = Modifier.padding(bottom = u * 0.35f))
                }
            }
        }
    }
}

private val Jersey2 = Color(0xFF2EC4B6)

/** Perfil do treino inteiro: uma barra por bloco (altura = watts, cor = zona) e onde você está. */
@Composable
private fun WorkoutStrip(live: LiveRide, track: SceneTrack, u: Dp, modifier: Modifier) {
    Box(modifier.background(GlassBg, RoundedCornerShape(u * 1.6f)).border(1.dp, GlassLine, RoundedCornerShape(u * 1.6f))
        .padding(horizontal = u * 1.2f, vertical = u * 0.9f)) {
        if (track.blockCount == 0) return@Box
        val total = track.totalSec.coerceAtLeast(1)
        val maxW = track.watts.maxOf { it ?: 0 }.coerceAtLeast(1)
        val cur = live.coach?.blockIndex ?: -1
        Canvas(Modifier.fillMaxSize()) {
            val gap = 2.dp.toPx()
            for (i in 0 until track.blockCount) {
                val x0 = size.width * track.starts[i] / total
                val w = size.width * track.durations[i] / total - gap
                val h = size.height * (0.28f + 0.72f * (track.watts[i] ?: 0) / maxW)
                drawRoundRect(
                    zoneColor(track.zones[i]).copy(alpha = if (i == cur) 1f else 0.55f),
                    topLeft = Offset(x0, size.height - h), size = Size(w.coerceAtLeast(1f), h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            val hx = size.width * (live.elapsedSec.toFloat() / total).coerceIn(0f, 1f)
            drawRect(Color.White, topLeft = Offset(hx - 1.5.dp.toPx(), -4.dp.toPx()), size = Size(3.dp.toPx(), size.height + 4.dp.toPx()))
        }
    }
}

private fun shortZoneName(z: Int) = when (z) {
    1 -> "Recuperação"; 2 -> "Endurance"; 3 -> "Tempo"; 4 -> "Limiar"; 5 -> "VO2 máx"; 6 -> "Anaeróbico"; else -> "Neuromuscular"
}

private fun nextLabel(track: SceneTrack, i: Int): String {
    val w = track.watts.getOrNull(i)
    val z = track.zones.getOrElse(i) { 0 }
    return when {
        w == null -> "livre"
        z > 0 -> "Z$z · $w W"
        else -> "$w W"
    }
}

/** m:ss (ou h:mm:ss), como no relógio da prévia. */
private fun fmtClock(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
