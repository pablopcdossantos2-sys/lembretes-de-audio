package com.example.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorderHelper(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    fun startRecording(outputFile: File): Boolean {
        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            false
        }
    }

    fun pauseRecording(): Boolean {
        return try {
            if (isRecording && !isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resumeRecording(): Boolean {
        return try {
            if (isRecording && isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                isPaused = false
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopRecording(): Boolean {
        return try {
            if (isRecording) {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false
                isPaused = false
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            false
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun createDefaultOutputFile(): File {
        val dir = File(context.filesDir, "lembretes_audio").apply { mkdirs() }
        return File(dir, "lembrete_${System.currentTimeMillis()}.m4a")
    }

    fun release() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
        isPaused = false
    }
}
