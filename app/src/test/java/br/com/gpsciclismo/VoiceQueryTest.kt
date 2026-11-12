package br.com.gpsciclismo

import br.com.gpsciclismo.core.VoiceQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceQueryTest {
    @Test fun understandsCommonQuestions() {
        assertEquals(VoiceQuery.SUMMARY, VoiceQuery.parse(listOf("me atualize")))
        assertEquals(VoiceQuery.BLOCK, VoiceQuery.parse(listOf("quanto tempo falta pro intervalo")))
        assertEquals(VoiceQuery.SPEED, VoiceQuery.parse(listOf("qual minha velocidade")))
        assertEquals(VoiceQuery.POWER, VoiceQuery.parse(listOf("qual a potência")))
        assertEquals(VoiceQuery.HEART_RATE, VoiceQuery.parse(listOf("como tá o coração")))
        assertEquals(VoiceQuery.CADENCE, VoiceQuery.parse(listOf("cadência")))
        assertEquals(VoiceQuery.DISTANCE, VoiceQuery.parse(listOf("quantos quilômetros eu andei")))
        assertEquals(VoiceQuery.TIME, VoiceQuery.parse(listOf("quanto tempo de treino")))
        assertEquals(VoiceQuery.UNKNOWN, VoiceQuery.parse(listOf("banana")))
        // usa a segunda alternativa se a primeira não fizer sentido
        assertEquals(VoiceQuery.SPEED, VoiceQuery.parse(listOf("banana", "velocidade")))
    }
}
