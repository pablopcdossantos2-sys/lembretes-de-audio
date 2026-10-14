package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AudioPlayerManager(private val context: Context) : TextToSpeech.OnInitListener {

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

    init {
        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "TTS initialization failed", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            textToSpeech?.language = Locale.forLanguageTag("pt-BR")
            try {
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (e: Exception) {
                Log.w("AudioPlayerManager", "Could not set TTS audio attributes", e)
            }
            pendingTtsText?.let { text ->
                val callback = pendingTtsOnComplete
                pendingTtsText = null
                pendingTtsOnComplete = null
                speakTts(text, onComplete = callback ?: {})
            }
        }
    }

    private fun prepareSpeakerVolume(volumeFraction: Float, useAppVolumeOnly: Boolean) {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (useAppVolumeOnly || currentVol == 0) {
                if (previousAlarmVolume == null) {
                    previousAlarmVolume = currentVol
                }
                val desiredVol = (maxVol * volumeFraction).toInt().coerceIn(1, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, desiredVol, 0)
            }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Não foi possível ajustar volume de alarme", e)
        }
    }

    private fun restoreSpeakerVolume() {
        try {
            previousAlarmVolume?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
                previousAlarmVolume = null
            }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Não foi possível restaurar volume de alarme", e)
        }
    }

    fun playAudio(
        uriString: String,
        targetVolume: Float = 1.0f,
        fadeInDurationMs: Long = 0L,
        useAppVolumeOnly: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        stop()
        requestAudioFocus()

        val clampedTargetVolume = targetVolume.coerceIn(0.01f, 1.0f)
        prepareSpeakerVolume(clampedTargetVolume, useAppVolumeOnly)

        // Check if this is a TTS audio item
        if (uriString.startsWith("tts:")) {
            val textToSpeak = uriString.removePrefix("tts:").replace("_", " ")
            speakTts(textToSpeak, clampedTargetVolume, useAppVolumeOnly, onComplete)
            return
        }

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                val uri = Uri.parse(uriString)
                if (uri.scheme == null || uri.scheme == "file") {
                    val filePath = uri.path ?: uriString
                    val file = File(filePath)
                    if (file.exists()) {
                        setDataSource(file.absolutePath)
                    } else {
                        throw IllegalArgumentException("Arquivo não encontrado: $filePath")
                    }
                } else {
                    setDataSource(context, uri)
                }

                setOnCompletionListener {
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("AudioPlayerManager", "MediaPlayer error: what=$what extra=$extra")
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
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
            Log.e("AudioPlayerManager", "Error playing audio $uriString", e)
            restoreSpeakerVolume()
            abandonAudioFocus()
            onComplete()
        }
    }

    fun speakTts(
        text: String,
        volume: Float = 1.0f,
        useAppVolumeOnly: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        if (!isTtsInitialized || textToSpeech == null) {
            pendingTtsText = text
            pendingTtsOnComplete = onComplete
            return
        }

        prepareSpeakerVolume(volume.coerceIn(0.1f, 1.0f), useAppVolumeOnly)
        requestAudioFocus()
        val utteranceId = "tts_${System.currentTimeMillis()}"

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }
            }
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    restoreSpeakerVolume()
                    abandonAudioFocus()
                    onComplete()
                }
            }
        })

        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.1f, 1.0f))
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun testSpeaker(
        volumePercent: Int = 100,
        useAppVolumeOnly: Boolean = true,
        onComplete: () -> Unit = {}
    ) {
        val volumeFraction = (volumePercent.coerceIn(10, 100)) / 100f
        val text = "Teste de som: o áudio está sendo reproduzido no alto-falante do celular com sucesso!"
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

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
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
                AudioManager.STREAM_ALARM,
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
