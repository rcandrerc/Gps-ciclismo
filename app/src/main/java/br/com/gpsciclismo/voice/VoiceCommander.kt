package br.com.gpsciclismo.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import br.com.gpsciclismo.core.VoiceQuery

/**
 * Perguntas por voz durante o pedal.
 *  - Botão do fone (play/pause): 1 toque = resumo completo, 2 toques = fazer uma pergunta.
 *  - Botão de microfone na tela do treino.
 */
class VoiceCommander(
    private val context: Context,
    private val voice: VoiceAnnouncer,
    private val onSummary: () -> Unit,
    private val onQuery: (VoiceQuery) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var presses = 0

    private val session = MediaSession(context, "GpsCiclismo").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ev == null) return false
                val ours = ev.keyCode in setOf(
                    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                )
                if (!ours) return false
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) onButtonPress()
                return true
            }
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        isActive = true
    }

    init {
        claimMediaButton()
    }

    private fun onButtonPress() {
        presses++
        if (presses == 1) {
            main.postDelayed({
                val n = presses
                presses = 0
                if (n >= 2) listen() else onSummary()
            }, 550)
        }
    }

    /**
     * O Android manda o botão do fone para o app que tocou som por último.
     * Toca meio segundo de silêncio para "pegar" o botão (se nenhum app de música estiver tocando).
     */
    fun claimMediaButton() {
        runCatching {
            val rate = 16000
            val samples = ShortArray(rate / 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            main.postDelayed({ runCatching { track.stop(); track.release() } }, 800)
        }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Fala "pode falar", bipa e escuta uma pergunta. */
    fun listen() {
        if (listening) return
        if (!hasMicPermission()) {
            voice.speak("Para fazer perguntas, libere o microfone para o app. Um toque no botão do fone dá o resumo completo.", urgent = true)
            return
        }
        if (!runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) {
            voice.speak(UNAVAILABLE, urgent = true)
            return
        }
        listening = true
        // Se algo não responder (ex.: a fala não terminar), libera para tentar de novo.
        main.postDelayed({ listening = false }, 20_000)
        voice.speak("Pode falar.", urgent = true) {
            voice.listenBeep()
            main.postDelayed({
                runCatching { startRecognizer() }.onFailure {
                    // Alguns tablets (ex.: Fire) dizem ter reconhecimento mas falham ao abrir.
                    listening = false
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    voice.speak(UNAVAILABLE)
                }
            }, 250)
        }
    }

    /** Prefere o reconhecimento do Google, se houver; senão o padrão do aparelho. */
    private fun createRecognizer(): SpeechRecognizer {
        val google = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }
                .firstOrNull { it.packageName.startsWith("com.google") }
                ?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(context, google)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun startRecognizer() {
        val r = recognizer ?: createRecognizer().also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val q = VoiceQuery.parse(alts)
                if (q == VoiceQuery.UNKNOWN) {
                    voice.speak("Não entendi. Pergunte velocidade, potência, batimento, cadência, distância, quanto falta, ou diga me atualize.")
                } else onQuery(q)
                claimMediaButton()
            }

            override fun onError(error: Int) {
                listening = false
                voice.speak(
                    if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                        "Sem internet para entender a pergunta. Um toque no botão do fone dá o resumo."
                    else "Não ouvi direito. Tente de novo ou dê um toque no botão do fone para o resumo.",
                )
                claimMediaButton()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        r.startListening(intent)
    }

    companion object {
        private const val UNAVAILABLE =
            "Este aparelho não tem reconhecimento de voz. Use o botão Resumo, ou um toque no botão do fone."
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        session.isActive = false
        session.release()
    }
}
