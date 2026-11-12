package br.com.gpsciclismo.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fala com o ciclista. Abaixa o volume de outras músicas enquanto fala
 * (audio focus com "duck") e devolve o volume no final.
 */
class VoiceAnnouncer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private val queuedBeforeReady = mutableListOf<String>()
    private val pending = AtomicInteger(0)
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("pt", "BR"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            tts.setAudioAttributes(attrs)
            tts.setSpeechRate(1.05f)
            ready = true
            queuedBeforeReady.forEach { speak(it) }
            queuedBeforeReady.clear()
        }
    }.also {
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = release()
            @Deprecated("API antiga") override fun onError(id: String?) = release()
        })
    }

    private fun release() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    var enabled = true

    fun speak(text: String, urgent: Boolean = false) {
        if (!enabled) return
        if (!ready) { queuedBeforeReady += text; return }
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (urgent) pending.set(1)
        tts.speak(text, mode, null, "u${System.nanoTime()}")
    }

    /** Bipe curto: agudo = suba, grave = baixe. */
    fun beep(high: Boolean) {
        if (!enabled) return
        tone?.startTone(
            if (high) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK,
            200,
        )
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        tone?.release()
        audio.abandonAudioFocusRequest(focus)
    }
}
