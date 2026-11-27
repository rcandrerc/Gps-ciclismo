package br.com.gpsciclismo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import br.com.gpsciclismo.core.CogganZones
import br.com.gpsciclismo.core.SceneTrack
import br.com.gpsciclismo.recording.LiveRide
import br.com.gpsciclismo.recording.RecStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Cena 2D do treino indoor: um ciclista numa estrada ao entardecer.
 * A estrada é o treino (bloco forte = subida), as pernas giram na cadência real,
 * e um ciclista "fantasma" transparente pedala exatamente no alvo do bloco.
 * Tudo é desenhado com formas simples em Canvas (sem imagens), a no máximo 30 quadros/s,
 * para rodar leve no tablet Fire.
 */

/** Cores das zonas do Coggan (Z1 a Z7); índice 0 = livre. */
val ZoneColors = listOf(
    Color(0xFF6B7488), Color(0xFF8E9BB5), Color(0xFF4AA3FF), Color(0xFF3DDC84),
    Color(0xFFFFD23F), Color(0xFFFF9B3D), Color(0xFFFF5A4E), Color(0xFFB77CFF),
)

fun zoneColor(zone: Int): Color = ZoneColors[zone.coerceIn(0, 7)]

private val Jersey = Color(0xFF2EC4B6)
private val JerseyShade = Color(0xFF1F8F86)
private val Coral = Color(0xFFFF6B4A)

/** Estado da animação (não é estado do Compose: muda a cada quadro sem recompor nada). */
class SceneSim {
    var t = 0.0               // tempo do treino, suavizado entre os segundos
    private var baseSec = -1
    private var frac = 0.0
    var power = 0f
    var cadence = 0f
    var crank = 0f
    var ghostCrank = 1.2f
    var dist = 0f             // distância "de textura" (faixas, postes, montanhas), em px de referência
    var lead = 0f             // vantagem do fantasma, em fração da largura
    var ghostTarget: Int? = null
    var effort = 0f
    /** Postura (0 = ereto, 1 = ataque), suavizada para trocar de posição sem pulo. */
    var posture = 0f
    var ghostPosture = 0f
    val winds = Array(36) { floatArrayOf(rnd(it), rnd(it + 50), rnd(it + 90)) }

    fun step(dt: Float, live: LiveRide, track: SceneTrack) {
        val running = live.status == RecStatus.RECORDING
        if (live.elapsedSec != baseSec) { baseSec = live.elapsedSec; frac = 0.0 }
        if (running) frac = min(frac + dt, 1.0)
        val target = baseSec + frac
        t = if (abs(target - t) > 3) target else t + (target - t) * min(1.0, dt * 8.0)

        val pw = if (running) (live.powerAvg ?: 0).toFloat() else 0f
        power += (pw - power) * min(1f, dt * 3f)
        val cadIn = when {
            !running -> 0f
            live.cadence != null -> live.cadence.toFloat()
            power > 20 -> 85f
            else -> 0f
        }
        cadence += (cadIn - cadence) * min(1f, dt * 2f)
        crank -= cadence / 60f * 2f * PI.toFloat() * dt
        effort = min(1f, power / 250f)
        // muda de postura devagar (~1,5 s), para não ficar mexendo a cada pico de potência
        posture += (track.postureAt(power) - posture) * min(1f, dt * 0.7f)

        val speed = when {
            !running -> 0f
            live.speedKmh > 1 -> live.speedKmh.toFloat() * 2.6f
            power > 5 || cadence > 5 -> 22f + power * 0.27f
            else -> 0f
        }
        dist += speed * dt

        val coach = live.coach
        ghostTarget = coach?.let { c ->
            c.ergWatts ?: c.range?.let { r -> if (c.ftpTest || r.last >= 9999) r.first else (r.first + r.last) / 2 }
        }
        val gt = ghostTarget
        if (gt != null && running) {
            lead += (power - gt) * 0.0016f * dt
            lead *= 1f - 0.12f * dt
            lead = lead.coerceIn(-0.16f, 0.16f)
            ghostCrank -= 90f / 60f * 2f * PI.toFloat() * dt
        } else if (gt == null) lead = 0f
        val gp = if (gt != null) track.postureAt(gt.toFloat()) else 0f
        ghostPosture += (gp - ghostPosture) * min(1f, dt * 0.7f)

        if (running) {
            val drift = dt * (0.4f + power / 300f)
            winds.forEachIndexed { i, w ->
                w[0] -= drift * (1.2f + w[2])
                if (w[0] < -0.1f) { w[0] = 1.1f; w[1] = rnd(i + baseSec) }
            }
        }
    }
}

private fun rnd(i: Int): Float {
    val x = sin(i * 127.1 + 311.7) * 43758.5453
    return (x - floor(x)).toFloat()
}

@Composable
fun RideScene(live: LiveRide, track: SceneTrack, modifier: Modifier = Modifier) {
    val sim = remember { SceneSim() }
    val liveNow by rememberUpdatedState(live)
    val trackNow by rememberUpdatedState(track)
    var frame by remember { mutableLongStateOf(0L) }
    val measurer = rememberTextMeasurer()
    val painter = remember(measurer) { ScenePainter(measurer) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = (now - last) / 1e9f
                if (dt >= 1f / 31f) {       // no máximo ~30 quadros/s
                    last = now
                    sim.step(min(dt, 0.1f), liveNow, trackNow)
                    frame = now
                }
            }
        }
    }

    Canvas(modifier) {
        if (frame >= 0L) painter.draw(this, sim, trackNow)   // ler o quadro faz redesenhar a cada passo
    }
}

/** Desenha a cena. Reaproveita os Paths para não criar lixo a cada quadro. */
private class ScenePainter(private val measurer: TextMeasurer) {
    private val p1 = Path()
    private val p2 = Path()
    private val tmp = Path()
    private val ghostPaint = Paint().apply { alpha = 0.3f }
    private val signText = HashMap<String, TextLayoutResult>()

    private var W = 0f; private var H = 0f; private var s = 1f
    private var pps = 1f; private var rx = 0f; private var baseY = 0f

    fun draw(ds: DrawScope, sim: SceneSim, track: SceneTrack) = with(ds) {
        W = size.width; H = size.height
        if (W <= 0f || H <= 0f) return@with
        s = W / 1100f
        pps = W * 0.62f / LOOKAHEAD_SEC
        rx = W * 0.40f
        baseY = H * 0.70f

        drawSky(sim)
        layer(sim, 0.06f, H * 0.50f, 0.07f, 1.3f, Color(0xBF9680A5))
        layer(sim, 0.16f, H * 0.58f, 0.06f, 4.2f, Color(0xFF5D5986))
        layer(sim, 0.38f, H * 0.66f, 0.05f, 7.9f, Color(0xFF36405F))
        trees(sim)
        drawGround(sim, track)
        drawWind(sim)

        // fantasma (camada transparente única, para não manchar onde as partes se cruzam)
        val gt = sim.ghostTarget
        if (gt != null) {
            val gx = rx - sim.lead * W
            val gy = baseY
            drawIntoCanvas { it.saveLayer(Rect(gx - 120 * s, gy - 230 * s, gx + 140 * s, gy + 20 * s), ghostPaint) }
            drawRider(gx, gy + s, sim.ghostCrank, wheelRot(sim), true, 0.6f, sim.ghostPosture)
            drawIntoCanvas { it.restore() }
            val lbl = label("ALVO", 11f, FontWeight.SemiBold, Color.White.copy(alpha = 0.75f))
            drawText(lbl, topLeft = Offset(gx + 10 * s - lbl.size.width / 2f, gy - (172 - 20 * sim.ghostPosture) * s))
        }
        // sombra e ciclista
        val ry = baseY
        drawOval(Color.Black.copy(alpha = 0.28f), Offset(rx - 62 * s, ry - 2 * s), Size(124 * s, 10 * s))
        drawRider(rx, ry + s, sim.crank, wheelRot(sim), false, sim.effort, sim.posture)
    }

    private fun wheelRot(sim: SceneSim) = -sim.dist / 1.25f / 30f


    // ---------- fundo ----------

    private fun DrawScope.drawSky(sim: SceneSim) {
        drawRect(Brush.verticalGradient(
            0f to Color(0xFF101A33), 0.38f to Color(0xFF34406C), 0.66f to Color(0xFFD9845F), 0.82f to Color(0xFFF4BF86),
            startY = 0f, endY = H,
        ))
        val sun = Offset(W * 0.72f, H * 0.50f)
        drawRect(Brush.radialGradient(
            0f to Color(0xF2FFECBE), 0.12f to Color(0xBFFFD696), 0.4f to Color(0x2EFFAA6E), 1f to Color(0x00FFAA6E),
            center = sun, radius = W * 0.22f,
        ))
        drawCircle(Color(0xFFFFF1CF), W * 0.035f, sun)
        val off = sim.dist * s * 0.04f
        for (i in 0 until 6) {
            val span = W * 1.6f
            val x = ((rnd(i) * span - off) % span + span) % span - W * 0.3f
            val y = H * (0.12f + rnd(i + 7) * 0.25f)
            val w = W * (0.12f + rnd(i + 3) * 0.14f)
            val c = Color(1f, (200 + rnd(i) * 30) / 255f, (180 + rnd(i + 1) * 40) / 255f, 0.10f + rnd(i + 2) * 0.10f)
            drawOval(c, Offset(x - w, y - w * 0.12f), Size(2 * w, w * 0.24f))
            drawOval(c, Offset(x + w * 0.35f - w * 0.5f, y - w * 0.06f - w * 0.11f), Size(w, w * 0.22f))
        }
    }

    private fun ridge(x: Float, seed: Float, amp: Float): Float =
        amp * (0.55f * sin(x * 0.0021f + seed) + 0.3f * sin(x * 0.0057f + seed * 2.3f) + 0.15f * sin(x * 0.013f + seed * 4.1f))

    private var nearOff = 0f

    private fun DrawScope.layer(sim: SceneSim, f: Float, yBase: Float, amp: Float, seed: Float, color: Color) {
        val off = sim.dist * s * f
        p1.reset(); p1.moveTo(0f, H)
        var x = 0f
        while (x <= W + 10f) { p1.lineTo(x, yBase + ridge((x + off) / s, seed, amp * H)); x += 10f }
        p1.lineTo(W, H); p1.close()
        drawPath(p1, color)
        if (f == 0.38f) nearOff = off
    }

    private fun DrawScope.trees(sim: SceneSim) {
        val sp = 46f * s
        val i0 = floor(nearOff / sp).toInt() - 1
        val i1 = ((nearOff + W) / sp).toInt() + 1
        for (i in i0..i1) {
            if (rnd(i) < 0.35f) continue
            val x = i * sp - nearOff + rnd(i + 5) * sp * 0.6f
            val y = H * 0.66f + ridge((x + nearOff) / s, 7.9f, 0.05f * H) + 2f
            val h = 30f * s * (0.6f + rnd(i + 9) * 0.8f); val w = h * 0.36f
            tmp.reset(); tmp.moveTo(x, y - h); tmp.lineTo(x + w, y); tmp.lineTo(x - w, y); tmp.close()
            drawPath(tmp, Color(0xFF2B3450))
        }
    }

    // ---------- estrada ----------

    private fun DrawScope.drawGround(sim: SceneSim, track: SceneTrack) {
        val step = 8f
        // campo abaixo da estrada
        p1.reset(); p1.moveTo(-10f, H)
        p2.reset()
        var x = -10f; var first = true
        while (x <= W + 10f) {
            val y = baseY
            p1.lineTo(x, y - 6 * s)
            if (first) { p2.moveTo(x, y + 6 * s); first = false } else p2.lineTo(x, y + 6 * s)
            x += step
        }
        p1.lineTo(W + 10f, H); p1.close()
        drawPath(p1, Brush.verticalGradient(0f to Color(0xFF2E3A55), 1f to Color(0xFF141C2C), startY = H * 0.5f, endY = H))
        // borda iluminada pelo sol
        tmp.reset(); x = -10f; first = true
        while (x <= W + 10f) {
            val y = baseY - 7 * s
            if (first) { tmp.moveTo(x, y); first = false } else tmp.lineTo(x, y)
            x += step
        }
        drawPath(tmp, Color(0x59FFBE82), style = Stroke(2 * s))
        // asfalto
        drawPath(p2, Color(0xFF1A1E27), style = Stroke(26 * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(p2, Color(0xFF2A2F3B), style = Stroke(20 * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        withTransform({ translate(0f, -9.5f * s) }) { drawPath(p2, Color(0x8CEBE2CD), style = Stroke(1.5f * s)) }
        val dash = 40f * s
        drawPath(p2, Color(0xFFEFE2C2), style = Stroke(2.2f * s, pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(22 * s, 18 * s), (sim.dist * s) % dash)))
        // postes de beira de estrada (andam com a velocidade)
        val sp = 70f * s
        val off = (sim.dist * s) % sp
        var px = -off
        while (px <= W + sp) {
            val y = baseY - 6 * s
            drawRect(Color(0xFFD9D2C1), Offset(px - 1.5f * s, y - 16 * s), Size(3 * s, 16 * s))
            drawRect(Color(0xFFFF7A3D), Offset(px - 1.5f * s, y - 15 * s), Size(3 * s, 3 * s))
            px += sp
        }
        // placas: próximo bloco (zona e watts) e chegada
        if (track.blockCount > 0) {
            for (i in 1 until track.blockCount) {
                val tb = track.starts[i] - 6.0
                val sx = rx + ((tb - sim.t) * pps).toFloat()
                if (sx < -120 * s || sx > W + 120 * s) continue
                val z = track.zones[i]
                val w = track.watts[i]
                val top = if (w != null) (if (z > 0) "Z$z · $w W" else "$w W") else "LIVRE"
                val sub = if (z > 0) CogganZones.zone(z).name.uppercase() else "PEDAL LIVRE"
                sign(sx, baseY - 7 * s, top, sub, zoneColor(z))
            }
            val tf = track.totalSec - 2.0
            val fx = rx + ((tf - sim.t) * pps).toFloat()
            if (fx > -120 * s && fx < W + 120 * s) sign(fx, baseY - 7 * s, "CHEGADA", "FIM DO TREINO", Color.White)
        }
    }

    /** Texto com tamanho em px de referência (escala junto com a cena). Fica em cache. */
    private fun DrawScope.label(text: String, sizePx: Float, weight: FontWeight, color: Color): TextLayoutResult {
        val key = "$text|$sizePx|$weight|${color.value}|$s"
        signText[key]?.let { return it }
        if (signText.size > 80) signText.clear()
        val sp = (sizePx * s / (density * fontScale)).sp
        return measurer.measure(text, TextStyle(fontSize = sp, fontWeight = weight, color = color, fontFamily = Condensed))
            .also { signText[key] = it }
    }

    private fun DrawScope.sign(x: Float, y: Float, top: String, sub: String, col: Color) {
        val a = label(top, 15f, FontWeight.Bold, col)
        val b = label(sub, 10.5f, FontWeight.SemiBold, Color(0xFFD8DDE8))
        val w = max(a.size.width, b.size.width) + 24 * s
        val h = a.size.height + b.size.height + 6 * s
        drawRect(Color(0xFFCFC7B4), Offset(x - 2 * s, y - 58 * s), Size(4 * s, 58 * s))
        val bx = x - w / 2; val by = y - 58 * s - h
        drawRoundRect(Color(0xD90A0E18), Offset(bx, by), Size(w, h), CornerRadius(6 * s))
        drawRoundRect(col, Offset(bx, by), Size(9 * s, h), CornerRadius(6 * s))
        drawRect(Color(0xD90A0E18), Offset(bx + 6 * s, by), Size(4 * s, h))
        drawText(a, topLeft = Offset(bx + 14 * s, by + 3 * s))
        drawText(b, topLeft = Offset(bx + 14 * s, by + 3 * s + a.size.height))
    }

    private fun DrawScope.drawWind(sim: SceneSim) {
        val n = ((sim.power - 80f) / 7f).roundToInt().coerceIn(0, sim.winds.size)
        for (i in 0 until n) {
            val w = sim.winds[i]
            val x = w[0] * W; val y = H * (0.35f + w[1] * 0.42f)
            drawLine(Color(0x38FFF0DC), Offset(x, y), Offset(x + (20 + w[2] * 40) * s, y), 1.2f * s)
        }
    }

    // ---------- ciclista ----------

    private class Pal(
        val o: Color?, val frame: Color, val frame2: Color, val decal: Color, val tire: Color, val rim: Color,
        val jersey: Color, val jersey2: Color, val stripe: Color, val bib: Color, val bib2: Color,
        val skin: Color, val skin2: Color, val sock: Color, val sock2: Color, val shoe: Color, val shoe2: Color,
        val helmet: Color, val visor: Color, val glove: Color, val metal: Color, val metal2: Color,
        val chain: Color, val saddle: Color, val ring: Color,
    )

    private val solid = Pal(
        o = Color(0xFF151821), frame = Color(0xFFF4F1EA), frame2 = Color(0xFFD9D4C8), decal = Coral,
        tire = Color(0xFF121419), rim = Color(0xFF2A2F3B), jersey = Jersey, jersey2 = JerseyShade, stripe = Color.White,
        bib = Color(0xFF191C24), bib2 = Color(0xFF0C0D12), skin = Color(0xFFE2A47C), skin2 = Color(0xFFB77650),
        sock = Color.White, sock2 = Color(0xFFC9CCD3), shoe = Coral, shoe2 = Color(0xFFC0533B),
        helmet = Color.White, visor = Color(0xFF1C2233), glove = Color(0xFF191C24), metal = Color(0xFFC4CAD6),
        metal2 = Color(0xFF7D8494), chain = Color(0xFF5A6170), saddle = Color(0xFF191C24), ring = Color(0xFF8F97A8),
    )
    private val ghostPal = run {
        val g = Color.White; val f = Color(0xFFC9D2E2)
        Pal(null, g, g, g, g, f, g, g, g, g, f, g, f, g, f, g, f, g, g, g, g, f, f, g, g)
    }

    private fun DrawScope.limb(a: Offset, b: Offset, wa: Float, wb: Float, c: Color) {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l = max(hypot(dx, dy), 0.001f)
        val nx = -dy / l; val ny = dx / l
        tmp.reset()
        tmp.moveTo(a.x + nx * wa / 2, a.y + ny * wa / 2); tmp.lineTo(b.x + nx * wb / 2, b.y + ny * wb / 2)
        tmp.lineTo(b.x - nx * wb / 2, b.y - ny * wb / 2); tmp.lineTo(a.x - nx * wa / 2, a.y - ny * wa / 2); tmp.close()
        drawPath(tmp, c)
        drawCircle(c, wa / 2, a); drawCircle(c, wb / 2, b)
    }

    private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun ik(h: Offset, p: Offset, l1: Float, l2: Float, sign: Float): Offset {
        val dx = p.x - h.x; val dy = p.y - h.y
        val d = hypot(dx, dy).coerceIn(abs(l1 - l2) + 0.01f, l1 + l2 - 0.01f)
        val a = atan2(dy, dx)
        val b = acos(((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f))
        val kk = a + sign * b
        return Offset(h.x + l1 * cos(kk), h.y + l1 * sin(kk))
    }

    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    /**
     * Ciclista de perfil, virado para a direita. Coordenadas locais com y para cima.
     * [posture]: 0 = tronco ereto, mãos em cima do guidão (zonas leves);
     * 1 = posição de ataque, tronco baixo, cotovelos dobrados e mãos na curva de baixo (zonas altas).
     */
    private fun DrawScope.drawRider(x: Float, y: Float, cr: Float, wr: Float, ghost: Boolean, effort: Float, posture: Float) {
        val K = if (ghost) ghostPal else solid
        val O = 1.7f
        fun L(a: Offset, b: Offset, wa: Float, wb: Float, c: Color) {
            K.o?.let { limb(a, b, wa + O, wb + O, it) }
            limb(a, b, wa, wb, c)
        }
        val sc = s * 1.25f
        withTransform({
            translate(x, y)
            scale(sc, -sc, Offset.Zero)
        }) {
            val R = 30f
            val rear = Offset(-34f, R); val front = Offset(36f, R); val bb = Offset(-6f, R - 4)
            val bob = sin(cr * 2) * 0.7f * effort
            val q = posture.coerceIn(0f, 1f)
            // tronco gira no quadril: 44° (ereto) até 16° (ataque); no ataque o quadril vai para a ponta do selim
            val th = lerpF(TORSO_UP, TORSO_ATTACK, q)
            val hip = Offset(-17f + lerpF(-1.5f, 1.5f, q), 77 + bob)
            val sh = Offset(hip.x + TORSO_LEN * cos(th), hip.y + TORSO_LEN * sin(th) - bob * 0.4f)
            val nk = th + lerpF(0.10f, 0.42f, q)       // no ataque a cabeça levanta para olhar a estrada
            val hc = Offset(sh.x + 14.2f * cos(nk), sh.y + 14.2f * sin(nk) - bob * 0.1f)
            fun pedal(a: Float) = Offset(bb.x + 10 * cos(a), bb.y + 10 * sin(a))

            fun leg(a: Float, far: Boolean) {
                val p = pedal(a)
                val an = Offset(p.x - 1.2f, p.y + 4f)
                val kn = ik(hip, an, 31f, 28f, 1f)
                val skin = if (far) K.skin2 else K.skin
                val bib = if (far) K.bib2 else K.bib
                val sEnd = lerp(hip, kn, 0.74f)
                L(sEnd, kn, 9f, 7.6f, skin)
                L(hip, sEnd, 12.5f, 10f, bib)
                L(kn, an, 7.4f, 4.2f, skin)
                // panturrilha
                val dx = an.x - kn.x; val dy = an.y - kn.y; val l = max(hypot(dx, dy), 0.001f)
                val nx = -dy / l; val ny = dx / l; val sg = if (nx < 0) 1f else -1f
                val cm = lerp(kn, an, 0.32f)
                val cc = Offset(cm.x + nx * sg * 2.2f, cm.y + ny * sg * 2.2f)
                withTransform({ rotate(deg(atan2(dy, dx)), cc) }) {
                    drawOval(skin, Offset(cc.x - 7f, cc.y - 3.4f), Size(14f, 6.8f))
                }
                limb(lerp(kn, an, 0.78f), an, 4.7f, 4.3f, if (far) K.sock2 else K.sock)
                // sapatilha
                withTransform({ translate(p.x, p.y); rotate(deg(sin(a) * 0.18f), Offset.Zero) }) {
                    K.o?.let { drawRoundRect(it, Offset(-5.6f, -1.6f), Size(14.2f, 7.2f), CornerRadius(3.2f)) }
                    drawRoundRect(if (far) K.shoe2 else K.shoe, Offset(-4.8f, -0.8f), Size(12.6f, 5.6f), CornerRadius(2.6f))
                    if (!ghost) {
                        drawRect(if (far) Color(0xFF0B0C10) else Color(0xFF1A1D25), Offset(-4.8f, -0.8f), Size(12.6f, 1.4f))
                        drawRect(Color.White.copy(alpha = 0.8f), Offset(0f, 2.2f), Size(4f, 1f))
                    }
                }
                L(bb, p, 3f, 2.4f, if (far) K.metal2 else K.metal)
            }

            leg(cr + PI.toFloat(), true)

            // rodas de perfil alto
            for (c in arrayOf(rear, front)) {
                drawCircle(K.tire, R - 2.3f, c, style = Stroke(4.6f))
                drawCircle(K.rim, R - 8f, c, style = Stroke(7f))
                if (!ghost) {
                    drawCircle(Color.White.copy(alpha = 0.18f), R - 4.6f, c, style = Stroke(1f))
                    val rr = R - 8f
                    for (kk in 0..1) drawArc(
                        Jersey, deg(wr) + kk * 180f, deg(0.55f), false,
                        Offset(c.x - rr, c.y - rr), Size(2 * rr, 2 * rr), style = Stroke(3.4f),
                    )
                    for (i in 0 until 10) {
                        val a = wr + i * PI.toFloat() / 5
                        drawLine(Color(0x59E1E6F0), c, Offset(c.x + (R - 11.5f) * cos(a), c.y + (R - 11.5f) * sin(a)), 0.6f)
                    }
                }
                drawCircle(K.metal, 2.6f, c)
            }

            // corrente
            val cog = 3.6f; val ring = 8f
            drawLine(K.chain, Offset(bb.x, bb.y + ring), Offset(rear.x, rear.y + cog), 1.1f)
            drawLine(K.chain, Offset(bb.x, bb.y - ring), Offset(rear.x, rear.y - cog), 1.1f)
            drawCircle(K.chain, cog + 0.6f, rear)

            // quadro
            val st = Offset(-15f, 63f); val ht = Offset(25f, 64f); val htb = Offset(28f, 55f)
            L(bb, rear, 3.4f, 2.6f, K.frame2)
            L(st, rear, 2.8f, 2.4f, K.frame2)
            L(bb, htb, 6f, 5f, K.frame)
            L(bb, st, 5f, 4.2f, K.frame)
            L(st, ht, 4f, 4.4f, K.frame)
            L(htb, front, 4.2f, 2.6f, K.frame)
            L(htb, ht, 6.2f, 6.2f, K.decal)
            if (!ghost) {
                limb(lerp(bb, htb, 0.28f), lerp(bb, htb, 0.62f), 2.2f, 2.2f, Jersey)
                limb(lerp(bb, st, 0.3f), lerp(bb, st, 0.5f), 1.6f, 1.6f, Coral)
            }
            K.o?.let { drawCircle(it, ring + 1f, bb) }
            drawCircle(K.ring, ring, bb)
            if (!ghost) drawCircle(Color(0xFF2A2F3B), ring - 2.6f, bb)

            // canote e selim
            L(st, Offset(-18f, 71f), 3f, 3f, K.saddle)
            withTransform({ translate(-18f, 72.5f) }) {
                K.o?.let {
                    tmp.reset(); tmp.moveTo(-10f, 1.2f); tmp.quadraticBezierTo(-3f, 4.6f, 8.5f, 1.6f)
                    tmp.lineTo(8.5f, -1.6f); tmp.quadraticBezierTo(-2f, -0.6f, -10f, -2.4f); tmp.close()
                    drawPath(tmp, it)
                }
                tmp.reset(); tmp.moveTo(-9f, 0.6f); tmp.quadraticBezierTo(-3f, 3.6f, 7.6f, 1f)
                tmp.lineTo(7.6f, -0.8f); tmp.quadraticBezierTo(-2f, 0f, -9f, -1.6f); tmp.close()
                drawPath(tmp, K.saddle)
            }
            // mesa e guidão de estrada
            L(ht, Offset(33f, 68f), 3.2f, 3f, K.saddle)
            tmp.reset(); tmp.moveTo(33f, 68f); tmp.quadraticBezierTo(42f, 67.5f, 38.5f, 60f); tmp.quadraticBezierTo(36f, 56.5f, 32f, 58f)
            K.o?.let { drawPath(tmp, it, style = Stroke(4.6f, cap = StrokeCap.Round)) }
            drawPath(tmp, K.saddle, style = Stroke(3f, cap = StrokeCap.Round))

            // tronco (camisa): desenhado na pose de referência e girado no quadril
            withTransform({
                translate(hip.x + 17f, 0f)
                rotate(deg(th - TORSO_REF), Offset(-17f, 77 + bob))
            }) {
            p1.reset()
            p1.moveTo(-23f, 78 + bob); p1.quadraticBezierTo(-8f, 102 + bob, 13f, 104 + bob * 0.6f)
            p1.quadraticBezierTo(21f, 103 + bob * 0.6f, 21f, 96 + bob * 0.6f)
            p1.quadraticBezierTo(4f, 84 + bob, -6f, 70 + bob); p1.quadraticBezierTo(-16f, 68 + bob, -23f, 78 + bob); p1.close()
            K.o?.let { drawPath(p1, it, style = Stroke(O * 2, join = StrokeJoin.Round)) }
            drawPath(p1, K.jersey)
            if (!ghost) clipPath(p1) {
                tmp.reset(); tmp.moveTo(-30f, 60f); tmp.lineTo(30f, 90f); tmp.lineTo(30f, 60f); tmp.close()
                drawPath(tmp, K.jersey2)
                tmp.reset(); tmp.moveTo(-20f, 87 + bob); tmp.quadraticBezierTo(-4f, 98 + bob, 16f, 101 + bob * 0.6f)
                drawPath(tmp, K.stripe, style = Stroke(3.2f))
                tmp.reset(); tmp.moveTo(-21f, 83.5f + bob); tmp.quadraticBezierTo(-5f, 94.5f + bob, 17f, 97.5f + bob * 0.6f)
                drawPath(tmp, Coral, style = Stroke(1.4f))
            }
            }
            L(hip, Offset(hip.x + 6f, 74 + bob), 13f, 12f, K.bib)

            leg(cr, false)

            // pescoço e cabeça
            L(Offset(sh.x + 3, sh.y + 2), Offset(hc.x - 3, hc.y - 3), 5f, 4.6f, K.skin)
            K.o?.let { drawCircle(it, 7.4f, hc) }
            drawCircle(K.skin, 6.6f, hc)
            if (!ghost) {
                drawOval(K.skin, Offset(hc.x + 6.2f - 1.6f, hc.y - 1.4f - 1.3f), Size(3.2f, 2.6f))
                drawCircle(K.skin2, 1.5f, Offset(hc.x - 1.2f, hc.y - 0.6f))
                drawCircle(Color(0xFF3A2A22), 2.6f, Offset(hc.x - 4.2f, hc.y - 2.2f))
            }
            // óculos
            tmp.reset(); tmp.moveTo(hc.x + 1.5f, hc.y + 1.6f); tmp.lineTo(hc.x + 8.4f, hc.y + 1.8f)
            tmp.quadraticBezierTo(hc.x + 8.6f, hc.y - 1.6f, hc.x + 5f, hc.y - 1.4f); tmp.lineTo(hc.x + 1.5f, hc.y - 0.2f); tmp.close()
            drawPath(tmp, K.visor)
            if (!ghost) {
                tmp.reset(); tmp.moveTo(hc.x + 4.6f, hc.y + 1.3f); tmp.lineTo(hc.x + 7.6f, hc.y + 1.4f); tmp.lineTo(hc.x + 6.6f, hc.y); tmp.close()
                drawPath(tmp, Jersey)
            }
            // capacete aero
            p2.reset()
            p2.moveTo(hc.x + 8.2f, hc.y + 2.6f); p2.quadraticBezierTo(hc.x + 6f, hc.y + 11.5f, hc.x - 3f, hc.y + 10.4f)
            p2.quadraticBezierTo(hc.x - 11f, hc.y + 8.5f, hc.x - 14f, hc.y + 3.2f); p2.lineTo(hc.x - 6f, hc.y + 2.4f); p2.close()
            K.o?.let { drawPath(p2, it, style = Stroke(O * 2, join = StrokeJoin.Round)) }
            drawPath(p2, K.helmet)
            if (!ghost) clipPath(p2) {
                tmp.reset(); tmp.moveTo(hc.x - 16f, hc.y + 3f); tmp.quadraticBezierTo(hc.x - 2f, hc.y + 9f, hc.x + 9f, hc.y + 4.2f)
                tmp.lineTo(hc.x + 9f, hc.y + 2f); tmp.lineTo(hc.x - 16f, hc.y + 1f); tmp.close()
                drawPath(tmp, Jersey)
                drawLine(K.visor, Offset(hc.x - 6f, hc.y + 8.6f), Offset(hc.x - 1f, hc.y + 9.6f), 1.3f, cap = StrokeCap.Round)
                drawLine(K.visor, Offset(hc.x, hc.y + 9.4f), Offset(hc.x + 4f, hc.y + 8.8f), 1.3f, cap = StrokeCap.Round)
            }

            // braço: manga, antebraço, luva
            // mãos: em cima (ereto) → manete → curva de baixo (ataque)
            val hand = if (q < 0.5f) lerp(HAND_TOPS, HAND_HOODS, q * 2) else lerp(HAND_HOODS, HAND_DROPS, q * 2 - 1)
            val el = ik(sh, hand, 21f, 21f, -1f)
            val slv = lerp(sh, el, 0.55f)
            L(slv, el, 5.6f, 5f, K.skin)
            L(el, hand, 5f, 4.2f, K.skin)
            L(sh, slv, 8.6f, 7.4f, K.jersey)
            if (!ghost) limb(lerp(sh, el, 0.48f), slv, 7.6f, 7.4f, K.stripe)
            K.o?.let { drawCircle(it, 3.6f, hand) }
            drawCircle(K.glove, 2.9f, hand)
        }
    }

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val TORSO_LEN = 37.4f
        private val TORSO_REF = atan2(21f, 31f)               // ângulo do tronco no desenho de referência
        private val TORSO_UP = Math.toRadians(44.0).toFloat()
        private val TORSO_ATTACK = Math.toRadians(16.0).toFloat()
        private val HAND_TOPS = Offset(32f, 68.6f)
        private val HAND_HOODS = Offset(38.6f, 66f)
        private val HAND_DROPS = Offset(36.5f, 58.8f)

        /** Segundos do treino que cabem à frente do ciclista na tela (dá para ver a próxima placa chegando). */
        const val LOOKAHEAD_SEC = 75f
    }
}
