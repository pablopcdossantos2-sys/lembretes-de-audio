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
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
            // Unmute streams if currently muted
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}

            val targetStream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(targetStream)
            val currentVol = audioManager.getStreamVolume(targetStream)

            // Garante volume mínimo audível (pelo menos 70% do volume se estiver muito baixo ou mudo)
            val minAudibleVol = (maxVol * 0.70f).toInt().coerceAtLeast(1)
            val desiredVol = (maxVol * volumeFraction).toInt().coerceIn(minAudibleVol, maxVol)

            if (!useAppVolumeOnly && (currentVol < desiredVol || currentVol == 0)) {
                if (previousMusicVolume == null) {
                    previousMusicVolume = currentVol
                }
                try {
                    audioManager.setStreamVolume(targetStream, desiredVol, 0)
                } catch (_: Exception) {}
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.ROUTING,
                    message = "Volume de mídia ajustado para disparo: STREAM_MUSIC",
                    details = "Volume anterior=$currentVol -> Novo=$desiredVol (Max=$maxVol, Fração=${(volumeFraction * 100).toInt()}%)"
                )
            }

            // Garante também volume no stream de alarmes de forma segura
            try {
                val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val currentAlarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (currentAlarm < (maxAlarm * 0.5f).toInt()) {
                    if (previousAlarmVolume == null) {
                        previousAlarmVolume = currentAlarm
                    }
                    val desiredAlarm = (maxAlarm * volumeFraction).toInt().coerceIn(1, maxAlarm)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, desiredAlarm, 0)
                }
            } catch (_: Exception) {}
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
        try {
            stop()

            val isHeadphones = try { DeviceStateHelper.isHeadphonesConnected(context) } catch (_: Throwable) { false }
            val deviceDesc = try { DeviceStateHelper.getConnectedAudioDevicesDescription(context) } catch (_: Throwable) { "Alto-falante do Celular" }
            try { AudioDiagnosticLogger.logAudioRouting(isHeadphones, deviceDesc) } catch (_: Throwable) {}

            try { requestAudioFocus(isHeadphones) } catch (_: Throwable) {}

            val clampedTargetVolume = targetVolume.coerceIn(0.1f, 1.0f)
            try { prepareAudioVolume(clampedTargetVolume, useAppVolumeOnly, isHeadphones) } catch (_: Throwable) {}

            val streamTypeStr = if (isHeadphones) "STREAM_MUSIC (Fones)" else "STREAM_ALARM / STREAM_MUSIC (Alto-falante)"
            try {
                AudioDiagnosticLogger.logPlaybackStart(
                    title = audioTitle,
                    uri = uriString,
                    isHeadphones = isHeadphones,
                    volumePercent = (clampedTargetVolume * 100).toInt(),
                    streamType = streamTypeStr,
                    details = "Fade-in: ${fadeInDurationMs}ms"
                )
            } catch (_: Throwable) {}

            // Resolve caminho do arquivo no armazenamento local
            val uri = try { Uri.parse(uriString) } catch (_: Throwable) { null }
            val cleanPath = when {
                uri == null -> uriString
                uri.scheme == null -> uriString
                uri.scheme == "file" -> uri.path ?: uriString.removePrefix("file://").removePrefix("file:")
                else -> null
            }
            val targetFile = cleanPath?.let { File(it) }
            val hasRealAudioFile = targetFile != null && targetFile.exists() && targetFile.canRead() && targetFile.length() > 0

            // Se for um arquivo de som gravado ou arquivo local existente, toca via MediaPlayer
            if (hasRealAudioFile) {
                playMediaPlayerFile(
                    file = targetFile!!,
                    audioTitle = audioTitle,
                    clampedTargetVolume = clampedTargetVolume,
                    fadeInDurationMs = fadeInDurationMs,
                    onComplete = onComplete
                )
                return
            }

            // Se for um URI content:// (ex: importado do sistema de arquivos)
            if (uri != null && uri.scheme == "content") {
                playMediaPlayerSource(
                    sourceUri = uri,
                    filePath = null,
                    audioTitle = audioTitle,
                    clampedTargetVolume = clampedTargetVolume,
                    fadeInDurationMs = fadeInDurationMs,
                    onComplete = onComplete
                )
                return
            }

            // Se for um item TTS com chave correspondente (ex: tts:beba_agua), verifica se temos o chime WAV equivalente
            val fallbackFiles = try {
                com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(context)
            } catch (_: Throwable) {
                emptyList()
            }

            val matchingChime = when {
                uriString.contains("agua") -> fallbackFiles.getOrNull(0)
                uriString.contains("postura") -> fallbackFiles.getOrNull(1)
                uriString.contains("respiracao") -> fallbackFiles.getOrNull(2)
                else -> fallbackFiles.firstOrNull()
            }

            if (matchingChime != null && matchingChime.exists() && matchingChime.length() > 0) {
                // Toca o chime melódico agradável com MediaPlayer e, se o TTS estiver disponível, fala em seguida
                playMediaPlayerFile(
                    file = matchingChime,
                    audioTitle = audioTitle,
                    clampedTargetVolume = clampedTargetVolume,
                    fadeInDurationMs = fadeInDurationMs,
                    isChimeBeforeTts = true,
                    onComplete = {
                        val textToSpeak = when {
                            !ttsText.isNullOrBlank() -> ttsText
                            else -> uriString.removePrefix("tts:").replace("_", " ")
                        }
                        if (textToSpeak.isNotBlank()) {
                            speakTts(
                                text = textToSpeak,
                                volume = clampedTargetVolume,
                                useAppVolumeOnly = useAppVolumeOnly,
                                onComplete = onComplete
                            )
                        } else {
                            restoreSpeakerVolume()
                            abandonAudioFocus()
                            onComplete()
                        }
                    }
                )
                return
            }

            // Caso contrário, sintetiza diretamente via TTS com watchdog timeout
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
        } catch (e: Throwable) {
            Log.e(TAG, "playAudio critical exception", e)
            playFallbackTone(targetVolume.coerceIn(0.1f, 1.0f), onComplete)
        }
    }

    private fun playMediaPlayerFile(
        file: File,
        audioTitle: String,
        clampedTargetVolume: Float,
        fadeInDurationMs: Long,
        isChimeBeforeTts: Boolean = false,
        onComplete: () -> Unit
    ) {
        playMediaPlayerSource(
            sourceUri = null,
            filePath = file.absolutePath,
            audioTitle = audioTitle,
            clampedTargetVolume = clampedTargetVolume,
            fadeInDurationMs = fadeInDurationMs,
            isChimeBeforeTts = isChimeBeforeTts,
            onComplete = onComplete
        )
    }

    private fun playMediaPlayerSource(
        sourceUri: Uri?,
        filePath: String?,
        audioTitle: String,
        clampedTargetVolume: Float,
        fadeInDurationMs: Long,
        isChimeBeforeTts: Boolean = false,
        onComplete: () -> Unit
    ) {
        try {
            val startTimeMs = System.currentTimeMillis()
            val isHeadphones = try { DeviceStateHelper.isHeadphonesConnected(context) } catch (_: Throwable) { false }
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                if (filePath != null && File(filePath).exists() && File(filePath).canRead()) {
                    setDataSource(filePath)
                } else if (sourceUri != null && sourceUri.scheme == "content") {
                    setDataSource(context, sourceUri)
                } else if (sourceUri != null && (sourceUri.scheme == "file" || sourceUri.scheme == null)) {
                    val p = sourceUri.path ?: sourceUri.toString().removePrefix("file://")
                    setDataSource(p)
                } else {
                    throw IllegalArgumentException("Arquivo de áudio não encontrado")
                }

                setOnCompletionListener {
                    val duration = System.currentTimeMillis() - startTimeMs
                    try { AudioDiagnosticLogger.logPlaybackEnd(audioTitle, duration, success = true) } catch (_: Throwable) {}
                    if (!isChimeBeforeTts) {
                        restoreSpeakerVolume()
                        abandonAudioFocus()
                    }
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    try {
                        AudioDiagnosticLogger.logPlaybackError(
                            title = audioTitle,
                            errorMessage = "MediaPlayer erro interno (what=$what, extra=$extra)",
                            details = "Executando tom sonoro de emergência para não deixar o lembrete silencioso"
                        )
                    } catch (_: Throwable) {}
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
            try {
                AudioDiagnosticLogger.logPlaybackError(
                    title = audioTitle,
                    errorMessage = "Falha ao abrir mídia: ${e.message}",
                    details = "Acionando tom alternativo audível"
                )
            } catch (_: Throwable) {}
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
        val clampedVol = volume.coerceIn(0.1f, 1.0f)
        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)

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
                        message = "Timeout na inicialização do TTS (2.5s), tocando som alternativo",
                        details = "Garante que o usuário ouça o lembrete mesmo sem motor de voz"
                    )
                    pendingTtsText = null
                    pendingTtsOnComplete = null
                    playFallbackTone(clampedVol, onComplete)
                }
            }, 2500L)
            return
        }

        prepareAudioVolume(clampedVol, useAppVolumeOnly, isHeadphones)
        requestAudioFocus(isHeadphones)
        val utteranceId = "tts_${System.currentTimeMillis()}"
        val startTimeMs = System.currentTimeMillis()

        var hasFinished = false

        // Watchdog timeout para o TTS: se a síntese de voz travar ou não emitir callbacks dentro de 4s, executa fallback
        val watchdogRunnable = Runnable {
            if (!hasFinished) {
                hasFinished = true
                AudioDiagnosticLogger.logPlaybackError("TTS: $text", "Timeout de execução do sintetizador TTS (4s)")
                playFallbackTone(clampedVol, onComplete)
            }
        }
        mainHandler.postDelayed(watchdogRunnable, 4000L)

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
                mainHandler.removeCallbacks(watchdogRunnable)
                if (!hasFinished) {
                    hasFinished = true
                    mainHandler.post {
                        val dur = System.currentTimeMillis() - startTimeMs
                        AudioDiagnosticLogger.logPlaybackEnd("TTS: $text", dur, success = true)
                        restoreSpeakerVolume()
                        abandonAudioFocus()
                        onComplete()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.removeCallbacks(watchdogRunnable)
                if (!hasFinished) {
                    hasFinished = true
                    mainHandler.post {
                        AudioDiagnosticLogger.logPlaybackError("TTS: $text", "Erro na síntese de fala do TTS")
                        restoreSpeakerVolume()
                        abandonAudioFocus()
                        playFallbackTone(clampedVol, onComplete)
                    }
                }
            }
        })

        val streamType = AudioManager.STREAM_MUSIC
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clampedVol)
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType)

        val speakResult = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (speakResult != TextToSpeech.SUCCESS) {
            mainHandler.removeCallbacks(watchdogRunnable)
            hasFinished = true
            AudioDiagnosticLogger.logPlaybackError(
                title = "TTS: $text",
                errorMessage = "Chamada a speak() retornou código de erro: $speakResult",
                details = "Executando tom de alarme sonoro como fallback"
            )
            playFallbackTone(clampedVol, onComplete)
        }
    }

    /**
     * Tom de alarme sonoro de emergência usando arquivo WAV de teste e ToneGenerator.
     * Garante que o usuário NUNCA fique sem ouvir um lembrete, mesmo que
     * o arquivo esteja corrompido ou o TTS não possua voz instalada.
     */
    private fun playFallbackTone(volumeFraction: Float, onComplete: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                val testFile = com.example.util.AudioSampleGenerator.getTestSpeakerFile(context)
                if (testFile.exists() && testFile.canRead()) {
                    playMediaPlayerFile(
                        file = testFile,
                        audioTitle = "Alerta Sonoro de Lembrete",
                        clampedTargetVolume = volumeFraction,
                        fadeInDurationMs = 0L,
                        onComplete = onComplete
                    )
                    return@launch
                }
            } catch (_: Exception) {}

            // Fallback via ToneGenerator se não for possível abrir o arquivo
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = AudioManager.STREAM_MUSIC
                    val volPercent = (volumeFraction * 100).toInt().coerceIn(75, 100)
                    val toneGen = ToneGenerator(stream, volPercent)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 900)
                    delay(1000L)
                    toneGen.release()
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.SUCCESS,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Tom sonoro alternativo executado com sucesso",
                        details = "Stream: STREAM_MUSIC | Volume: $volPercent%"
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
    }

    fun testSpeaker(
        volumePercent: Int = 100,
        useAppVolumeOnly: Boolean = true,
        onComplete: () -> Unit = {}
    ) {
        val volumeFraction = (volumePercent.coerceIn(10, 100)) / 100f
        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)

        // Toca primeiro o som de teste em WAV melódico no STREAM_MUSIC
        val testFile = com.example.util.AudioSampleGenerator.getTestSpeakerFile(context)
        if (testFile.exists() && testFile.canRead()) {
            prepareAudioVolume(volumeFraction, useAppVolumeOnly, isHeadphones)
            requestAudioFocus(isHeadphones)
            playMediaPlayerFile(
                file = testFile,
                audioTitle = "Teste de Alto-falante",
                clampedTargetVolume = volumeFraction,
                fadeInDurationMs = 0L,
                onComplete = {
                    val speechPhrase = if (isHeadphones) {
                        "Teste de som: o áudio está sendo reproduzido nos seus fones com sucesso!"
                    } else {
                        "Teste de som: o áudio está sendo reproduzido no alto-falante com sucesso!"
                    }
                    speakTts(
                        text = speechPhrase,
                        volume = volumeFraction,
                        useAppVolumeOnly = useAppVolumeOnly,
                        onComplete = onComplete
                    )
                }
            )
        } else {
            val text = if (isHeadphones) {
                "Teste de som: o áudio está sendo reproduzido nos seus fones de ouvido com sucesso!"
            } else {
                "Teste de som: o áudio está sendo reproduzido no alto-falante do celular com sucesso!"
            }
            speakTts(text, volume = volumeFraction, useAppVolumeOnly = useAppVolumeOnly, onComplete = onComplete)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { /* handle if needed */ }
                .build()
            audioFocusRequest = request
            try {
                audioManager.requestAudioFocus(request)
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            try {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            } catch (_: Exception) {}
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
