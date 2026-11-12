package br.com.gpsciclismo.core

import java.text.Normalizer

/** O que o ciclista pode perguntar durante o pedal. */
enum class VoiceQuery {
    SUMMARY, BLOCK, SPEED, POWER, HEART_RATE, CADENCE, DISTANCE, TIME, UNKNOWN;

    companion object {
        private fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        private val rules: List<Pair<VoiceQuery, List<String>>> = listOf(
            SUMMARY to listOf("atualiz", "resumo", "tudo", "relatorio"),
            BLOCK to listOf("falta", "intervalo", "bloco", "serie", "proximo", "tiro", "descanso"),
            POWER to listOf("potencia", "watt", "wats", "zona", "forca"),
            HEART_RATE to listOf("batimento", "coracao", "cardiac", "frequencia", "bpm", "pulso"),
            CADENCE to listOf("cadencia", "rotac", "rpm", "giro", "pedalada"),
            SPEED to listOf("velocidade", "rapido", "media", "maxima", "km por hora", "quilometros por hora"),
            DISTANCE to listOf("distancia", "quilometr", "km", "andei", "rodei", "percorri"),
            TIME to listOf("tempo", "hora", "minuto", "quanto tempo"),
            SUMMARY to listOf("status", "como estou", "como to", "como ta", "dados"),
        )

        /** Recebe as alternativas do reconhecimento de voz e escolhe a pergunta. */
        fun parse(alternatives: List<String>): VoiceQuery {
            for (alt in alternatives) {
                val t = normalize(alt)
                rules.firstOrNull { (_, keys) -> keys.any { it in t } }?.let { return it.first }
            }
            return UNKNOWN
        }
    }
}
