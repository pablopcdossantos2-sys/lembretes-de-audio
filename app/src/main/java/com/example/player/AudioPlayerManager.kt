package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.util.AudioDiagnosticLogger
import com.example.util.DeviceStateHelper
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AudioPlayerManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioPlayerManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingTtsText: String? = null
    private var pendingTtsOnComplete: (() -> Unit)? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentFadeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var previousAlarmVolume: Int? = null
    private var previousMusicVolume: Int? = null

    init {
        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TTS initialization exception", e)
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "TTS falhou ao instanciar: ${e.message}",
                details = "O sistema usará tons de alarme e arquivos gravados como alternativa"
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            try {
                val locale = Locale.forLanguageTag("pt-BR")
                var langResult = textToSpeech?.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val defaultLocale = Locale.getDefault()
                    val defResult = textToSpeech?.setLanguage(defaultLocale)
                    if (defResult == TextToSpeech.LANG_MISSING_DATA || defResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        langResult = textToSpeech?.setLanguage(Locale.US)
                    } else {
                        langResult = defResult
                    }
                }
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.PLAYBACK,
                    message = "Sintetizador TTS inicializado com sucesso",
                    details = "Status de idioma: $langResult"
                )
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not set TTS audio attributes", e)
            }
            pendingTtsText?.let { text ->
                val callback = pendingTtsOnComplete
                pendingTtsText = null
                pendingTtsOnComplete = null
                speakTts(text, onComplete = callback ?: {})
            }
        } else {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "TTS inicializado com código de status não-sucesso ($status)",
                details = "Disparos de voz sintetizada usarão tom sonoro alternativo"
            )
        }
    }

    private fun prepareAudioVolume(volumeFraction: Float, useAppVolumeOnly: Boolean, isHeadphones: Boolean) {
        try {
            val targetStream = if (isHeadphones) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM
            val maxVol = audioManager.getStreamMaxVolume(targetStream)
            val currentVol = audioManager.getStreamVolume(targetStream)

            // Garante volume mínimo audível (pelo menos 65% do volume se estiver muito baixo ou mudo)
            val minAudibleVol = (maxVol * 0.65f).toInt().coerceAtLeast(1)
            val desiredVol = (maxVol * volumeFraction).toInt().coerceIn(minAudibleVol, maxVol)

            if (useAppVolumeOnly || currentVol < desiredVol || currentVol == 0) {
                if (isHeadphones && previousMusicVolume == null) {
                    previousMusicVolume = currentVol
                } else if (!isHeadphones && previousAlarmVolume == null) {
                    previousAlarmVolume = currentVol
                }
                audioManager.setStreamVolume(targetStream, desiredVol, 0)
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.ROUTING,
                    message = "Volume ajustado para disparo: Stream ${if (isHeadphones) "MUSIC (Fones)" else "ALARM (Speaker)"}",
                    details = "Volume anterior=$currentVol -> Novo=$desiredVol (Max=$maxVol, Fração=${(volumeFraction * 100).toInt()}%)"
                )
            }

            // Garante que o stream secundário não esteja completamente mudo
            val secondaryStream = if (isHeadphones) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
            val currentSecondaryVol = audioManager.getStreamVolume(secondaryStream)
            if (currentSecondaryVol == 0) {
                val maxSecondary = audioManager.getStreamMaxVolume(secondaryStream)
                val desiredSecondary = (maxSecondary * volumeFraction).toInt().coerceIn(1, maxSecondary)
                audioManager.setStreamVolume(secondaryStream, desiredSecondary, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível ajustar volume de áudio", e)
        }
    }

    private fun restoreSpeakerVolume() {
        try {
            previousAlarmVolume?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
                previousAlarmVolume = null
            }
            previousMusicVolume?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
                previousMusicVolume = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível restaurar volume de áudio", e)
        }
    }

    fun playAudio(
        uriString: String,
        targetVolume: Float = 1.0f,
        fadeInDurationMs: Long = 0L,
        useAppVolumeOnly: Boolean = false,
        audioTitle: String = "Lembrete em Áudio",
        ttsText: String? = null,
        onComplete: () -> Unit = {}
    ) {
        stop()

        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)
        val deviceDesc = DeviceStateHelper.getConnectedAudioDevicesDescription(context)
        AudioDiagnosticLogger.logAudioRouting(isHeadphones, deviceDesc)

        requestAudioFocus(isHeadphones)

        val clampedTargetVolume = targetVolume.coerceIn(0.01f, 1.0f)
        prepareAudioVolume(clampedTargetVolume, useAppVolumeOnly, isHeadphones)

        val streamTypeStr = if (isHeadphones) "STREAM_MUSIC (Fones)" else "STREAM_ALARM (Alto-falante)"
        AudioDiagnosticLogger.logPlaybackStart(
            title = audioTitle,
            uri = uriString,
            isHeadphones = isHeadphones,
            volumePercent = (clampedTargetVolume * 100).toInt(),
            streamType = streamTypeStr,
            details = "Fade-in: ${fadeInDurationMs}ms"
        )

        // Verifica se é item de voz sintetizada (TTS)
        if (uriString.startsWith("tts:") || !ttsText.isNullOrBlank()) {
            val textToSpeak = when {
                !ttsText.isNullOrBlank() -> ttsText
                else -> uriString.removePrefix("tts:").replace("_", " ")
            }
            speakTts(
                text = textToSpeak,
                volume = clampedTargetVolume,
                useAppVolumeOnly = useAppVolumeOnly,
                onComplete = onComplete
            )
            return
        }

        try {
            val startTimeMs = System.currentTimeMillis()
            val audioUsage = if (isHeadphones) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                val uri = Uri.parse(uriString)
                val cleanPath = when {
                    uri.scheme == null -> uriString
                    uri.scheme == "file" -> uri.path ?: uriString.removePrefix("file://").removePrefix("file:")
                    else -> null
                }

                if (cleanPath != null) {
                    val file = File(cleanPath)
                    if (file.exists() && file.canRead()) {
                        setDataSource(file.absolutePath)
                    } else {
                        setDataSource(context, uri)
                    }
                } else {
                    setDataSource(context, uri)
                }

                setOnCompletionListener {
                    val duration = System.currentTimeMillis() - startTimeMs
                    AudioDiagnosticLogger.logPlaybackEnd(audioTitle, duration, success = true)
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    AudioDiagnosticLogger.logPlaybackError(
                        title = audioTitle,
                        errorMessage = "MediaPlayer erro interno (what=$what, extra=$extra)",
                        details = "Executando tom sonoro de emergência para não deixar o lembrete silencioso"
                    )
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    playFallbackTone(clampedTargetVolume, onComplete)
                    true
                }

                prepare()
            }

            mediaPlayer = player

            if (fadeInDurationMs > 100) {
                player.setVolume(0f, 0f)
                player.start()
                startFadeIn(player, clampedTargetVolume, fadeInDurationMs)
            } else {
                player.setVolume(clampedTargetVolume, clampedTargetVolume)
                player.start()
            }
        } catch (e: Exception) {
            AudioDiagnosticLogger.logPlaybackError(
                title = audioTitle,
                errorMessage = "Falha ao abrir mídia: ${e.message}",
                details = "Acionando tom alternativo audível"
            )
            restoreSpeakerVolume()
            abandonAudioFocus()
            playFallbackTone(clampedTargetVolume, onComplete)
        }
    }

    fun speakTts(
        text: String,
        volume: Float = 1.0f,
        useAppVolumeOnly: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        if (!isTtsInitialized || textToSpeech == null) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.INFO,
                category = DiagnosticCategory.PLAYBACK,
                message = "Aguardando inicialização do TTS para falar: \"$text\"",
                details = "Texto colocado na fila de inicialização"
            )
            pendingTtsText = text
            pendingTtsOnComplete = onComplete
            // Timeout seguro caso o TTS nunca responda
            mainHandler.postDelayed({
                if (pendingTtsText != null) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.WARN,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Timeout na inicialização do TTS (3s), tocando tom alternativo",
                        details = "Garante que o usuário ouça o lembrete mesmo sem motor de voz"
                    )
                    pendingTtsText = null
                    pendingTtsOnComplete = null
                    playFallbackTone(volume, onComplete)
                }
            }, 3000L)
            return
        }

        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)
        val clampedVol = volume.coerceIn(0.1f, 1.0f)
        prepareAudioVolume(clampedVol, useAppVolumeOnly, isHeadphones)
        requestAudioFocus(isHeadphones)
        val utteranceId = "tts_${System.currentTimeMillis()}"
        val startTimeMs = System.currentTimeMillis()

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.PLAYBACK,
                    message = "TTS iniciou fala: \"$text\"",
                    details = "Rota: ${if (isHeadphones) "Fones" else "Alto-falante"}"
                )
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    val dur = System.currentTimeMillis() - startTimeMs
                    AudioDiagnosticLogger.logPlaybackEnd("TTS: $text", dur, success = true)
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    AudioDiagnosticLogger.logPlaybackError("TTS: $text", "Erro na síntese de fala do TTS")
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    playFallbackTone(clampedVol, onComplete)
                }
            }
        })

        val streamType = if (isHeadphones) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clampedVol)
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType)

        val speakResult = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (speakResult != TextToSpeech.SUCCESS) {
            AudioDiagnosticLogger.logPlaybackError(
                title = "TTS: $text",
                errorMessage = "Chamada a speak() retornou código de erro: $speakResult",
                details = "Executando tom de alarme sonoro como fallback"
            )
            playFallbackTone(clampedVol, onComplete)
        }
    }

    /**
     * Tom de alarme sonoro de emergência usando ToneGenerator.
     * Garante que o usuário NUNCA fique sem ouvir um lembrete, mesmo que
     * o arquivo esteja corrompido ou o TTS não possua voz instalada.
     */
    private fun playFallbackTone(volumeFraction: Float, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = if (DeviceStateHelper.isHeadphonesConnected(context)) {
                    AudioManager.STREAM_MUSIC
                } else {
                    AudioManager.STREAM_ALARM
                }
                val volPercent = (volumeFraction * 100).toInt().coerceIn(50, 100)
                val toneGen = ToneGenerator(stream, volPercent)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
                delay(800L)
                toneGen.release()
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.SUCCESS,
                    category = DiagnosticCategory.PLAYBACK,
                    message = "Tom sonoro alternativo executado com sucesso",
                    details = "Stream: $stream | Volume: $volPercent%"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fallback tone error", e)
            } finally {
                mainHandler.post {
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }
            }
        }
    }

    fun testSpeaker(
        volumePercent: Int = 100,
        useAppVolumeOnly: Boolean = true,
        onComplete: () -> Unit = {}
    ) {
        val volumeFraction = (volumePercent.coerceIn(10, 100)) / 100f
        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)
        val text = if (isHeadphones) {
            "Teste de som: o áudio está sendo reproduzido nos seus fones de ouvido com sucesso!"
        } else {
            "Teste de som: o áudio está sendo reproduzido no alto-falante do celular com sucesso!"
        }
        speakTts(text, volume = volumeFraction, useAppVolumeOnly = useAppVolumeOnly, onComplete = onComplete)
    }

    private fun startFadeIn(player: MediaPlayer, targetVolume: Float, durationMs: Long) {
        currentFadeJob?.cancel()
        currentFadeJob = scope.launch {
            val steps = 20
            val stepDelay = durationMs / steps
            for (i in 1..steps) {
                delay(stepDelay)
                if (mediaPlayer == player && player.isPlaying) {
                    val currentVol = (targetVolume * i) / steps
                    player.setVolume(currentVol, currentVol)
                } else {
                    break
                }
            }
        }
    }

    fun stop() {
        currentFadeJob?.cancel()
        currentFadeJob = null

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}

        restoreSpeakerVolume()
        abandonAudioFocus()
    }

    private fun requestAudioFocus(isHeadphones: Boolean = false) {
        val audioUsage = if (isHeadphones) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        val streamType = if (isHeadphones) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { /* handle if needed */ }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                streamType,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun release() {
        stop()
        try {
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (_: Exception) {}
    }
}
