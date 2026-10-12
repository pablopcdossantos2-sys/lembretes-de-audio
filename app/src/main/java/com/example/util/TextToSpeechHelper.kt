package com.example.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

class TextToSpeechHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isInitialized: Boolean = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ptBr = Locale("pt", "BR")
                val result = tts?.setLanguage(ptBr)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to default locale
                    tts?.language = Locale.getDefault()
                }
                isInitialized = true
            }
        }
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        onDone: () -> Unit
    ) {
        val engine = tts ?: return
        if (!isInitialized) return

        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        val utteranceId = "speak_${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId) onDone()
            }

            override fun onError(id: String?) {
                if (id == utteranceId) onDone()
            }
        })

        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun synthesizeToFile(
        text: String,
        outputFile: File,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        onComplete: (Boolean) -> Unit
    ) {
        val engine = tts
        if (engine == null || !isInitialized) {
            onComplete(false)
            return
        }

        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        val utteranceId = "synth_${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    onComplete(outputFile.exists() && outputFile.length() > 0)
                }
            }

            override fun onError(id: String?) {
                if (id == utteranceId) {
                    onComplete(false)
                }
            }
        })

        val params = Bundle()
        val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            onComplete(false)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}
