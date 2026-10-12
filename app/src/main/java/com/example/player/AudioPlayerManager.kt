package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var fadeInJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var isPlaying: Boolean = false
        private set

    /**
     * Plays the audio given by uriString.
     * @param targetVolume float between 0.0f and 1.0f
     * @param fadeInDurationMs fade-in duration in milliseconds (0 if disabled)
     * @param onComplete called when audio completes or errors
     */
    fun playAudio(
        uriString: String,
        targetVolume: Float,
        fadeInDurationMs: Long,
        onComplete: () -> Unit
    ) {
        stop()

        val uri = Uri.parse(uriString)
        val hasFocus = requestFocus()

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }

        try {
            player.setDataSource(context, uri)

            player.setOnCompletionListener {
                releasePlayer()
                onComplete()
            }

            player.setOnErrorListener { _, _, _ ->
                releasePlayer()
                onComplete()
                true
            }

            val startVol = if (fadeInDurationMs > 0) 0.05f else targetVolume
            player.setVolume(startVol, startVol)

            player.setOnPreparedListener { mp ->
                mp.start()
                isPlaying = true

                if (fadeInDurationMs > 0) {
                    startFadeIn(mp, targetVolume, fadeInDurationMs)
                }
            }

            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            e.printStackTrace()
            releasePlayer()
            onComplete()
        }
    }

    private fun startFadeIn(player: MediaPlayer, targetVolume: Float, durationMs: Long) {
        fadeInJob?.cancel()
        fadeInJob = scope.launch {
            val steps = 20
            val stepTime = (durationMs / steps).coerceAtLeast(20L)
            val volumeIncrement = (targetVolume - 0.05f) / steps

            var currentVol = 0.05f
            for (i in 1..steps) {
                delay(stepTime)
                currentVol = (currentVol + volumeIncrement).coerceAtMost(targetVolume)
                try {
                    player.setVolume(currentVol, currentVol)
                } catch (_: Exception) {
                    break
                }
            }
            try {
                player.setVolume(targetVolume, targetVolume)
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        fadeInJob?.cancel()
        fadeInJob = null
        releasePlayer()
    }

    private fun requestFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    stop()
                }
            }
            .build()
        audioFocusRequest = focusRequest
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun releasePlayer() {
        isPlaying = false
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        releaseFocus()
    }
}
