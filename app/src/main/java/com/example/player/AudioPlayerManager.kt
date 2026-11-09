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
    private data class PendingTtsRequest(
        val text: String,
        val volume: Float,
        val useAppVolumeOnly: Boolean,
        val onComplete: () -> Unit,
        val retryCount: Int = 0
    )
    private var pendingTtsRequest: PendingTtsRequest? = null
    private var ttsInitializationWatchdog: Runnable? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentFadeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ttsWatchdogRunnable: Runnable? = null
    private var activePlaybackCompletion: (() -> Unit)? = null

    private var previousMusicVolume: Int? = null
    private var currentVolumeFraction: Float = 1.0f
    private var isPausedDueToAudioFocus: Boolean = false

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
            try {
                var languageResult = textToSpeech?.setLanguage(Locale.forLanguageTag("pt-BR"))
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    languageResult = textToSpeech?.setLanguage(Locale.getDefault())
                        ?: TextToSpeech.LANG_NOT_SUPPORTED
                }
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    languageResult = textToSpeech?.setLanguage(Locale.US)
                        ?: TextToSpeech.LANG_NOT_SUPPORTED
                }

                isTtsInitialized = languageResult >= TextToSpeech.LANG_AVAILABLE
                if (isTtsInitialized) {
                    textToSpeech?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.INFO,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Sintetizador TTS inicializado com sucesso",
                        details = "Status de idioma: $languageResult"
                    )
                } else {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.WARN,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Nenhuma voz compatível está disponível no dispositivo",
                        details = "Status de idioma: $languageResult"
                    )
                }
            } catch (e: Exception) {
                isTtsInitialized = false
                Log.w(TAG, "Could not configure TTS language or audio attributes", e)
            }
        } else {
            isTtsInitialized = false
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "TTS inicializado com código de status não-sucesso ($status)",
                details = "O motor será recriado uma vez antes de usar o som alternativo"
            )
        }

        val pending = pendingTtsRequest ?: return
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
        ttsInitializationWatchdog = null
        pendingTtsRequest = null

        if (isTtsInitialized) {
            speakTtsInternal(pending)
        } else {
            retryTtsOrFallback(pending, "Falha ao inicializar o motor ou idioma TTS")
        }
    }

    private fun recreateTtsEngine() {
        isTtsInitialized = false
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null

        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (error: Exception) {
            Log.e(TAG, "Could not recreate TextToSpeech engine", error)
            val pending = pendingTtsRequest
            pendingTtsRequest = null
            if (pending != null) {
                playFallbackTone(pending.volume, pending.onComplete)
            }
        }
    }

    private fun queueTtsInitialization(request: PendingTtsRequest) {
        pendingTtsRequest = request
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)

        val watchdog = Runnable {
            val pending = pendingTtsRequest
            if (pending === request) {
                pendingTtsRequest = null
                retryTtsOrFallback(
                    request,
                    "Timeout de 2,5s na inicialização do motor TTS"
                )
            }
        }
        ttsInitializationWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 2500L)
    }

    private fun retryTtsOrFallback(request: PendingTtsRequest, reason: String) {
        AudioDiagnosticLogger.logPlaybackError("TTS: ${request.text}", reason)
        if (request.retryCount < 1) {
            val retry = request.copy(retryCount = request.retryCount + 1)
            queueTtsInitialization(retry)
            recreateTtsEngine()
        } else {
            pendingTtsRequest = null
            ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
            ttsInitializationWatchdog = null
            playFallbackTone(request.volume, request.onComplete)
        }
    }

    private fun prepareAudioVolume(volumeFraction: Float, useAppVolumeOnly: Boolean, isHeadphones: Boolean) {
        currentVolumeFraction = volumeFraction
        try {
            if (useAppVolumeOnly) {
                // Modo exclusivo de volume do aplicativo: preserva estritamente os volumes físicos do sistema
                return
            }

            // Unmute streams if currently muted
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}

            val targetStream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(targetStream)
            val currentVol = audioManager.getStreamVolume(targetStream)

            // Garante volume mínimo audível (pelo menos 70% do volume se estiver muito baixo ou mudo)
            val minAudibleVol = (maxVol * 0.70f).toInt().coerceAtLeast(1)
            val desiredVol = (maxVol * volumeFraction).toInt().coerceIn(minAudibleVol, maxVol)

            val shouldAdjust = currentVol < desiredVol || currentVol == 0

            if (shouldAdjust) {
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


        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível ajustar volume de áudio", e)
        }
    }

    private fun restoreSpeakerVolume() {
        try {
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
        lateinit var playbackCompletion: () -> Unit
        var completionInitialized = false
        try {
            stop()

            var hasCompleted = false
            playbackCompletion = {
                if (!hasCompleted) {
                    hasCompleted = true
                    if (activePlaybackCompletion === playbackCompletion) {
                        activePlaybackCompletion = null
                    }
                    onComplete()
                }
            }
            completionInitialized = true
            activePlaybackCompletion = playbackCompletion

            val isHeadphones = try { DeviceStateHelper.isHeadphonesConnected(context) } catch (_: Throwable) { false }
            val deviceDesc = try { DeviceStateHelper.getConnectedAudioDevicesDescription(context) } catch (_: Throwable) { "Alto-falante do Celular" }
            try { AudioDiagnosticLogger.logAudioRouting(isHeadphones, deviceDesc) } catch (_: Throwable) {}

            try { requestAudioFocus(isHeadphones) } catch (_: Throwable) {}

            val clampedTargetVolume = targetVolume.coerceIn(0.1f, 1.0f)
            try { prepareAudioVolume(clampedTargetVolume, useAppVolumeOnly, isHeadphones) } catch (_: Throwable) {}

            val streamTypeStr = if (isHeadphones) "STREAM_MUSIC (Fones)" else "STREAM_MUSIC (Alto-falante)"
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
                    onComplete = playbackCompletion
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
                    onComplete = playbackCompletion
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
                    fadeInDurationMs = 0L,
                    isChimeBeforeTts = true,
                    onComplete = {
                        val rawText = when {
                            !ttsText.isNullOrBlank() -> ttsText
                            uriString.startsWith("tts:") -> uriString.removePrefix("tts:").replace("_", " ")
                            audioTitle.isNotBlank() -> audioTitle
                            else -> "Lembrete em áudio"
                        }
                        val textToSpeak = if (rawText.startsWith("/") || rawText.contains(".wav") || rawText.contains(".m4a")) {
                            audioTitle.ifBlank { "Lembrete programado" }
                        } else rawText

                        if (textToSpeak.isNotBlank()) {
                            speakTts(
                                text = textToSpeak,
                                volume = clampedTargetVolume,
                                useAppVolumeOnly = useAppVolumeOnly,
                                onComplete = playbackCompletion
                            )
                        } else {
                            restoreSpeakerVolume()
                            abandonAudioFocus()
                            playbackCompletion()
                        }
                    }
                )
                return
            }

            // Caso contrário, sintetiza diretamente via TTS com watchdog timeout
            val textToSpeak = when {
                !ttsText.isNullOrBlank() -> ttsText
                uriString.startsWith("tts:") -> uriString.removePrefix("tts:").replace("_", " ")
                audioTitle.isNotBlank() -> audioTitle
                else -> "Lembrete em áudio"
            }
            speakTts(
                text = textToSpeak,
                volume = clampedTargetVolume,
                useAppVolumeOnly = useAppVolumeOnly,
                onComplete = playbackCompletion
            )
        } catch (e: Throwable) {
            Log.e(TAG, "playAudio critical exception", e)
            playFallbackTone(
                targetVolume.coerceIn(0.1f, 1.0f),
                if (completionInitialized) playbackCompletion else onComplete
            )
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

            val duration = try { player.duration } catch (_: Exception) { -1 }
            val effectiveFadeMs = if (duration in 1..4000) {
                (duration * 0.15f).toLong().coerceAtMost(fadeInDurationMs)
            } else if (duration > 4000) {
                fadeInDurationMs.coerceAtMost(2500L)
            } else {
                0L
            }

            if (effectiveFadeMs > 150) {
                val initialVol = (clampedTargetVolume * 0.4f)
                player.setVolume(initialVol, initialVol)
                player.start()
                startFadeIn(player, clampedTargetVolume, effectiveFadeMs, initialVol)
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
        speakTtsInternal(
            PendingTtsRequest(
                text = text,
                volume = volume.coerceIn(0.1f, 1.0f),
                useAppVolumeOnly = useAppVolumeOnly,
                onComplete = onComplete
            )
        )
    }

    private fun speakTtsInternal(request: PendingTtsRequest) {
        if (!isTtsInitialized || textToSpeech == null) {
            queueTtsInitialization(request)
            return
        }

        val clampedVol = request.volume
        val text = request.text
        val onComplete = request.onComplete
        val isHeadphones = DeviceStateHelper.isHeadphonesConnected(context)

        prepareAudioVolume(clampedVol, request.useAppVolumeOnly, isHeadphones)
        requestAudioFocus(isHeadphones)
        val utteranceId = "tts_${System.currentTimeMillis()}"
        val startTimeMs = System.currentTimeMillis()
        var hasFinished = false

        val timeoutDelay = (3000L + 90L * text.length).coerceIn(4000L, 60_000L)
        val watchdogRunnable = Runnable {
            if (!hasFinished) {
                hasFinished = true
                AudioDiagnosticLogger.logPlaybackError(
                    "TTS: $text",
                    "Timeout de execução do sintetizador TTS (${timeoutDelay}ms)"
                )
                try { textToSpeech?.stop() } catch (_: Exception) {}
                retryTtsOrFallback(request, "Timeout de execução do sintetizador TTS")
            }
        }

        ttsWatchdogRunnable?.let(mainHandler::removeCallbacks)
        ttsWatchdogRunnable = watchdogRunnable
        mainHandler.postDelayed(watchdogRunnable, timeoutDelay)

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.removeCallbacks(watchdogRunnable)
                mainHandler.postDelayed(watchdogRunnable, timeoutDelay)
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.PLAYBACK,
                    message = "TTS iniciou fala: \"$text\"",
                    details = "Rota: ${if (isHeadphones) "Fones" else "Alto-falante"}"
                )
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.removeCallbacks(watchdogRunnable)
                if (ttsWatchdogRunnable === watchdogRunnable) ttsWatchdogRunnable = null
                if (!hasFinished) {
                    hasFinished = true
                    mainHandler.post {
                        val duration = System.currentTimeMillis() - startTimeMs
                        AudioDiagnosticLogger.logPlaybackEnd("TTS: $text", duration, success = true)
                        restoreSpeakerVolume()
                        abandonAudioFocus()
                        onComplete()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                handleUtteranceError("Erro na síntese de fala do TTS")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceError("Erro na síntese de fala do TTS (código $errorCode)")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.removeCallbacks(watchdogRunnable)
                if (ttsWatchdogRunnable === watchdogRunnable) ttsWatchdogRunnable = null
                if (!hasFinished) {
                    hasFinished = true
                    mainHandler.post {
                        restoreSpeakerVolume()
                        abandonAudioFocus()
                        onComplete()
                    }
                }
            }

            private fun handleUtteranceError(reason: String) {
                mainHandler.removeCallbacks(watchdogRunnable)
                if (ttsWatchdogRunnable === watchdogRunnable) ttsWatchdogRunnable = null
                if (!hasFinished) {
                    hasFinished = true
                    mainHandler.post {
                        retryTtsOrFallback(request, reason)
                    }
                }
            }
        })

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clampedVol)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        val speakResult = textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId
        )
        if (speakResult != TextToSpeech.SUCCESS) {
            mainHandler.removeCallbacks(watchdogRunnable)
            if (ttsWatchdogRunnable === watchdogRunnable) ttsWatchdogRunnable = null
            if (!hasFinished) {
                hasFinished = true
                retryTtsOrFallback(
                    request,
                    "Chamada a speak() retornou código de erro: $speakResult"
                )
            }
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
                var toneGen: ToneGenerator? = null
                try {
                    val stream = AudioManager.STREAM_MUSIC
                    val volPercent = (volumeFraction * 100).toInt().coerceIn(75, 100)
                    toneGen = ToneGenerator(stream, volPercent)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 900)
                    delay(1000L)
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.SUCCESS,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Tom sonoro alternativo executado com sucesso",
                        details = "Stream: STREAM_MUSIC | Volume: $volPercent%"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback tone error", e)
                } finally {
                    try { toneGen?.release() } catch (releaseError: Exception) {
                        Log.w(TAG, "Could not release fallback ToneGenerator", releaseError)
                    }
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

    private fun startFadeIn(
        player: MediaPlayer,
        targetVolume: Float,
        durationMs: Long,
        startVolume: Float = 0f
    ) {
        currentFadeJob?.cancel()
        currentFadeJob = scope.launch {
            val steps = 15
            val stepDelay = (durationMs / steps).coerceAtLeast(15L)
            for (i in 1..steps) {
                delay(stepDelay)
                if (mediaPlayer == player && player.isPlaying) {
                    val progress = i.toFloat() / steps
                    val currentVol = startVolume + (targetVolume - startVolume) * progress
                    player.setVolume(currentVol, currentVol)
                } else {
                    break
                }
            }
        }
    }

    fun stop() {
        val completion = activePlaybackCompletion
        activePlaybackCompletion = null
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
        ttsInitializationWatchdog = null
        pendingTtsRequest = null
        ttsWatchdogRunnable?.let(mainHandler::removeCallbacks)
        ttsWatchdogRunnable = null
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

        isPausedDueToAudioFocus = false
        restoreSpeakerVolume()
        abandonAudioFocus()
        completion?.invoke()
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently - stopping playback")
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transiently - pausing playback")
                try {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        isPausedDueToAudioFocus = true
                    }
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus ducking - lowering volume")
                try {
                    val duckVol = (currentVolumeFraction * 0.2f).coerceIn(0.05f, 0.3f)
                    mediaPlayer?.setVolume(duckVol, duckVol)
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained - restoring volume and playback")
                try {
                    val targetVol = currentVolumeFraction.coerceIn(0.1f, 1.0f)
                    mediaPlayer?.setVolume(targetVol, targetVol)
                    if (isPausedDueToAudioFocus && mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                        mediaPlayer?.start()
                        isPausedDueToAudioFocus = false
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestAudioFocus(isHeadphones: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            try {
                audioManager.requestAudioFocus(request)
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            try {
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            } catch (_: Exception) {}
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
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
