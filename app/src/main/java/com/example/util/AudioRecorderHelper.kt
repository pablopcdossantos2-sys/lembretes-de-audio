package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

class AudioRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderHelper"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordingJob: Job? = null

    // AudioRecord instance for real-time PCM capture
    private var audioRecord: AudioRecord? = null

    // Fallback MediaRecorder instance
    private var fallbackMediaRecorder: MediaRecorder? = null
    private var isUsingFallback = false

    // Real-time Frequency Spectrum Analyzer
    private val frequencyAnalyzer = AudioFrequencyAnalyzer(
        sampleRate = SAMPLE_RATE,
        fftSize = 512,
        numBands = 36
    )

    // Reactive StateFlows for UI visualizer
    private val _frequencyData = MutableStateFlow(FloatArray(36) { 0.04f })
    val frequencyData: StateFlow<FloatArray> = _frequencyData.asStateFlow()

    private val _waveformData = MutableStateFlow(FloatArray(48))
    val waveformData: StateFlow<FloatArray> = _waveformData.asStateFlow()

    private val _decibels = MutableStateFlow(-60.0f)
    val decibels: StateFlow<Float> = _decibels.asStateFlow()

    private val _peakFrequencyHz = MutableStateFlow(0.0f)
    val peakFrequencyHz: StateFlow<Float> = _peakFrequencyHz.asStateFlow()

    private val _currentAmplitude = MutableStateFlow(0)
    val currentAmplitude: StateFlow<Int> = _currentAmplitude.asStateFlow()

    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    private var currentOutputFile: File? = null
    private var totalPcmBytesWritten: Long = 0L

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File): Boolean {
        try {
            stopRecording()
            currentOutputFile = outputFile
            totalPcmBytesWritten = 0L
            frequencyAnalyzer.reset()

            // Try AudioRecord first for raw PCM and real frequency analysis
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, 4096)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord constructor failed, trying fallback", e)
                null
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = record
                isUsingFallback = false

                // Prepare WAV output file
                val fos = FileOutputStream(outputFile)
                writeWavHeader(fos, 0) // write preliminary 44-byte header

                record.startRecording()
                isRecording = true
                isPaused = false

                val shortBuffer = ShortArray(512)
                val byteBuffer = ByteArray(1024)

                recordingJob = scope.launch {
                    try {
                        while (isActive && isRecording) {
                            if (isPaused) {
                                val idle = frequencyAnalyzer.idleResult()
                                _frequencyData.value = idle.frequencyBands
                                _waveformData.value = idle.waveformPoints
                                _decibels.value = -60f
                                kotlinx.coroutines.delay(80)
                                continue
                            }

                            val shortsRead = record.read(shortBuffer, 0, shortBuffer.size)
                            if (shortsRead > 0) {
                                // 1. Convert to little-endian bytes and write to WAV file
                                for (i in 0 until shortsRead) {
                                    val s = shortBuffer[i].toInt()
                                    byteBuffer[i * 2] = (s and 0xFF).toByte()
                                    byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                }
                                val bytesToWrite = shortsRead * 2
                                fos.write(byteBuffer, 0, bytesToWrite)
                                totalPcmBytesWritten += bytesToWrite

                                // 2. Calculate peak amplitude
                                var maxAmp = 0
                                for (i in 0 until shortsRead) {
                                    val a = abs(shortBuffer[i].toInt())
                                    if (a > maxAmp) maxAmp = a
                                }
                                _currentAmplitude.value = maxAmp

                                // 3. Compute real-time frequency spectrum & oscilloscope data
                                val result = frequencyAnalyzer.analyze(shortBuffer, shortsRead)
                                _frequencyData.value = result.frequencyBands
                                _waveformData.value = result.waveformPoints
                                _decibels.value = result.decibels
                                _peakFrequencyHz.value = result.peakFrequencyHz
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in PCM recording loop", e)
                    } finally {
                        try { fos.flush() } catch (_: Exception) {}
                        try { fos.close() } catch (_: Exception) {}
                    }
                }
                return true
            } else {
                Log.w(TAG, "AudioRecord state not initialized, using MediaRecorder fallback")
                return startFallbackMediaRecorder(outputFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            release()
            return false
        }
    }

    private fun startFallbackMediaRecorder(outputFile: File): Boolean {
        return try {
            // MediaRecorder com MPEG_4/AAC produz container MP4, não WAV.
            val fallbackOutputFile = if (outputFile.extension.equals("wav", ignoreCase = true)) {
                File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.m4a")
            } else {
                outputFile
            }
            fallbackOutputFile.parentFile?.mkdirs()
            fallbackOutputFile.delete()
            currentOutputFile = fallbackOutputFile

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
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(fallbackOutputFile.absolutePath)
                prepare()
                start()
            }

            fallbackMediaRecorder = recorder
            isUsingFallback = true
            isRecording = true
            isPaused = false

            // Periodic sampler for amplitude and synthetic voice frequency spectrum
            recordingJob = scope.launch {
                while (isActive && isRecording) {
                    if (!isPaused) {
                        val maxAmp = try { recorder.maxAmplitude } catch (_: Exception) { 0 }
                        _currentAmplitude.value = maxAmp
                        val normalized = (maxAmp / 32767f).coerceIn(0.04f, 1.0f)
                        val result = frequencyAnalyzer.simulateFromAmplitude(normalized)
                        _frequencyData.value = result.frequencyBands
                        _waveformData.value = result.waveformPoints
                        _decibels.value = result.decibels
                        _peakFrequencyHz.value = result.peakFrequencyHz
                    }
                    kotlinx.coroutines.delay(60)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback MediaRecorder failed", e)
            release()
            false
        }
    }

    fun pauseRecording(): Boolean {
        if (!isRecording || isPaused) return false
        isPaused = true
        if (isUsingFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { fallbackMediaRecorder?.pause() } catch (_: Exception) {}
        }
        return true
    }

    fun resumeRecording(): Boolean {
        if (!isRecording || !isPaused) return false
        isPaused = false
        if (isUsingFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { fallbackMediaRecorder?.resume() } catch (_: Exception) {}
        }
        return true
    }

    fun stopRecording(): Boolean {
        if (!isRecording) return false

        isRecording = false
        isPaused = false

        recordingJob?.cancel()
        recordingJob = null

        if (audioRecord != null) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord", e)
            }
            audioRecord = null

            // Update WAV header with total file sizes
            currentOutputFile?.let { file ->
                try {
                    updateWavHeader(file, totalPcmBytesWritten)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating WAV header", e)
                }
            }
        }

        if (fallbackMediaRecorder != null) {
            try {
                fallbackMediaRecorder?.stop()
                fallbackMediaRecorder?.reset()
                fallbackMediaRecorder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaRecorder", e)
            }
            fallbackMediaRecorder = null
        }

        _frequencyData.value = frequencyAnalyzer.idleResult().frequencyBands
        _waveformData.value = frequencyAnalyzer.idleResult().waveformPoints
        _decibels.value = -60f
        _currentAmplitude.value = 0

        return true
    }

    fun getMaxAmplitude(): Int {
        return _currentAmplitude.value
    }

    /** Retorna o arquivo real, inclusive quando a gravação precisou usar fallback AAC/M4A. */
    fun getCurrentOutputFile(): File? = currentOutputFile

    fun createDefaultOutputFile(): File {
        val dir = File(context.filesDir, "lembretes_audio").apply { mkdirs() }
        return File(dir, "lembrete_${System.currentTimeMillis()}.wav")
    }

    fun release() {
        stopRecording()
    }

    /**
     * Writes standard 44-byte RIFF WAV header for mono 16-bit 44.1kHz audio.
     */
    private fun writeWavHeader(fos: FileOutputStream, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = SAMPLE_RATE.toLong()
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM format
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // block align (1 * 16 / 8 = 2)
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        fos.write(header, 0, 44)
    }

    private fun updateWavHeader(wavFile: File, totalPcmBytes: Long) {
        if (!wavFile.exists() || totalPcmBytes <= 0) return
        val totalDataLen = totalPcmBytes + 36

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    ((totalDataLen shr 8) and 0xff).toByte(),
                    ((totalDataLen shr 16) and 0xff).toByte(),
                    ((totalDataLen shr 24) and 0xff).toByte()
                )
            )
            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (totalPcmBytes and 0xff).toByte(),
                    ((totalPcmBytes shr 8) and 0xff).toByte(),
                    ((totalPcmBytes shr 16) and 0xff).toByte(),
                    ((totalPcmBytes shr 24) and 0xff).toByte()
                )
            )
        }
    }
}
