package br.com.gpsciclismo.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import br.com.gpsciclismo.core.FtpTestTracker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta um treino salvo como CSV (um ponto por segundo) para mandar por WhatsApp,
 * Drive, e-mail ou para o Claude analisar. As linhas que começam com # trazem o resumo
 * e o plano do treino; o resto é a tabela.
 */
object RideExport {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    private fun d(v: Double?, decimals: Int) = v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""
    private fun i(v: Int?) = v?.toString() ?: ""

    fun csv(ride: RideEntity, points: List<RidePointEntity>): String {
        val sb = StringBuilder()
        fun meta(k: String, v: Any?) { if (v != null) sb.append("# ").append(k).append(": ").append(v).append('\n') }
        meta("app", "GPS Ciclismo")
        meta("treino", ride.workoutName ?: "Pedal livre")
        meta("inicio", iso.format(Date(ride.startedAt)))
        meta("duracao_s", ride.elapsedSec)
        meta("ftp_perfil_w", ride.ftpUsed)
        meta("distancia_km", d(ride.distanceM / 1000, 2))
        meta("potencia_media_w", ride.avgPower)
        meta("potencia_max_w", ride.maxPower)
        meta("fc_media_bpm", ride.avgHr.takeIf { it > 0 })
        meta("fc_max_bpm", ride.maxHr.takeIf { it > 0 })
        meta("cadencia_media_rpm", ride.avgCadence.takeIf { it > 0 })
        meta("aproveitamento_pct", ride.adherencePct)
        ride.testAvgPower?.let {
            meta("teste_ftp_media_w", it)
            meta("teste_ftp_segundos", ride.testSec)
            meta("teste_ftp_estimado_w", FtpTestTracker.ftpFrom(it))
        }
        meta("plano_json", ride.planJson?.replace('\n', ' '))
        sb.append("segundo,hora,bloco,potencia_w,fc_bpm,cadencia_rpm,velocidade_kmh,lat,lon,altitude_m\n")
        val t0 = points.firstOrNull()?.time ?: ride.startedAt
        points.forEach { p ->
            sb.append((p.time - t0) / 1000).append(',')
                .append(iso.format(Date(p.time))).append(',')
                .append(p.block?.let { it + 1 } ?: "").append(',')
                .append(i(p.power)).append(',')
                .append(i(p.heartRate)).append(',')
                .append(i(p.cadence)).append(',')
                .append(d(p.speedKmh, 1)).append(',')
                .append(d(p.lat, 6)).append(',')
                .append(d(p.lon, 6)).append(',')
                .append(d(p.altitude, 1)).append('\n')
        }
        return sb.toString()
    }

    fun fileName(ride: RideEntity): String {
        val name = (ride.workoutName ?: "pedal").lowercase(Locale("pt", "BR"))
            .replace(Regex("[^a-z0-9áàâãéêíóôõúç]+"), "-").trim('-').take(40)
        return "treino_${fileFmt.format(Date(ride.startedAt))}_$name.csv"
    }

    /** Grava o CSV no cache e abre o "Compartilhar" do Android. */
    fun share(context: Context, ride: RideEntity, points: List<RidePointEntity>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // só o último fica guardado
        val file = File(dir, fileName(ride))
        file.writeText(csv(ride, points))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Exportar treino").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
