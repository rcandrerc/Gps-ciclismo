package br.com.gpsciclismo.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Uma voz instalada no celular, para a tela de escolha. */
data class VoiceOption(val name: String, val label: String, val needsInternet: Boolean, val quality: Int)

/**
 * Fala com o ciclista. Abaixa o volume de outras músicas enquanto fala
 * (audio focus com "duck") e devolve o volume no final.
 */
class VoiceAnnouncer(
    context: Context,
    private val voiceName: String? = null,
    private val rate: Float = 1.05f,
    /** true = Serviços de fala do Google (se instalado); false = voz padrão do aparelho. */
    useGoogle: Boolean = false,
    private val onReady: (VoiceAnnouncer) -> Unit = {},
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val queuedBeforeReady = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val pending = AtomicInteger(0)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private val appContext = context.applicationContext
    private lateinit var tts: TextToSpeech
    /** Mecanismo em uso (Google, se instalado; senão o padrão do aparelho). */
    var engine: String? = null; private set

    init { create(if (useGoogle && googleInstalled(appContext)) GOOGLE_TTS else null) }

    private fun create(engineName: String?) {
        engine = engineName
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) onInit()
                else if (engineName != null) {
                    // Google não quis abrir (ex.: desativado): volta para o padrão do aparelho.
                    runCatching { tts.shutdown() }
                    create(null)
                }
            }
        }
        tts = if (engineName != null) TextToSpeech(appContext, listener, engineName) else TextToSpeech(appContext, listener)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished(id)
            @Deprecated("API antiga") override fun onError(id: String?) = finished(id)
            override fun onStop(id: String?, interrupted: Boolean) = finished(id)
        })
    }

    private fun onInit() {
        val r = tts.setLanguage(Locale("pt", "BR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        voiceName?.let { name -> runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()?.let { tts.voice = it } }
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(rate)
        ready = true
        onReady(this)
        queuedBeforeReady.forEach { (t, cb) -> speak(t, onDone = cb) }
        queuedBeforeReady.clear()
    }

    private fun finished(id: String?) {
        id?.let { callbacks.remove(it) }?.let { cb -> main.post(cb) }
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    var enabled = true

    /** Vozes em português instaladas, as melhores primeiro. */
    fun portugueseVoices(): List<VoiceOption> {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: return emptyList()
        return voices
            .filter { it.locale.language == "pt" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.locale.country != "BR" }, { -it.quality }, { it.isNetworkConnectionRequired }, { it.name }))
            .mapIndexed { i, v ->
                val country = if (v.locale.country == "BR") "Brasil" else v.locale.displayCountry
                val q = when {
                    v.quality >= Voice.QUALITY_VERY_HIGH -> "qualidade muito alta"
                    v.quality >= Voice.QUALITY_HIGH -> "qualidade alta"
                    else -> "qualidade normal"
                }
                VoiceOption(
                    name = v.name,
                    label = "Voz ${i + 1} · $country · $q" + if (v.isNetworkConnectionRequired) " · usa internet" else "",
                    needsInternet = v.isNetworkConnectionRequired,
                    quality = v.quality,
                )
            }
    }

    /** Toca uma amostra com a voz indicada (sem mudar a voz salva). */
    fun preview(voiceName: String?, rate: Float, text: String) {
        if (!ready) return
        val v = voiceName?.let { n -> runCatching { tts.voices?.firstOrNull { it.name == n } }.getOrNull() }
        if (v != null) tts.voice = v else tts.setLanguage(Locale("pt", "BR"))
        tts.setSpeechRate(rate)
        speak(text, urgent = true)
    }

    fun speak(text: String, urgent: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!enabled) { onDone?.let { main.post(it) }; return }
        if (!ready) { queuedBeforeReady += text to onDone; return }
        if (urgent) { tts.stop(); pending.set(0) }
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        val id = "u${System.nanoTime()}"
        if (onDone != null) callbacks[id] = onDone
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, id)
    }

    fun stopSpeaking() {
        tts.stop()
    }

    /** Bipe curto: agudo = suba, grave = baixe. */
    fun beep(high: Boolean) {
        if (!enabled) return
        tone?.startTone(
            if (high) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK,
            200,
        )
    }

    /** Bipe de "pode falar". */
    fun listenBeep() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    companion object {
        /** "Serviços de fala do Google": vozes bem mais naturais que a padrão de alguns tablets (ex.: Fire). */
        const val GOOGLE_TTS = "com.google.android.tts"

        fun googleInstalled(context: Context): Boolean = runCatching {
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .any { it.serviceInfo.packageName == GOOGLE_TTS }
        }.getOrDefault(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        tone?.release()
        audio.abandonAudioFocusRequest(focus)
    }
}
