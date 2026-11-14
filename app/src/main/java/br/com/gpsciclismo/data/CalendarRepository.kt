package br.com.gpsciclismo.data

import android.content.Context
import br.com.gpsciclismo.core.TrainingCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Baixa o plano do repositório no GitHub e guarda uma cópia no celular,
 * para o calendário funcionar sem internet (por exemplo, no meio do pedal).
 */
class CalendarRepository(context: Context) {
    private val cache = File(context.filesDir, "calendario.json")
    private val _calendar = MutableStateFlow(load())
    val calendar: StateFlow<TrainingCalendar?> = _calendar

    private fun load(): TrainingCalendar? =
        runCatching { if (cache.exists()) TrainingCalendar.parse(cache.readText()) else null }.getOrNull()

    /** Atualiza do GitHub. Retorna true se conseguiu; se falhar, mantém o que já estava salvo. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$URL_PLANO?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) return@runCatching false
                val text = conn.inputStream.bufferedReader().readText()
                val parsed = TrainingCalendar.parse(text) // só grava se o arquivo for válido
                cache.writeText(text)
                _calendar.value = parsed
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        const val URL_PLANO = "https://raw.githubusercontent.com/rcandrerc/Gps-ciclismo/main/plano/calendario.json"
    }
}
