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
import kotlinx.coroutines.SupervisorJob
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var stopFinalizationJob: Job? = null

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
            if (stopFinalizationJob?.isActive == true) {
                Log.w(TAG, "Previous recording is still being finalized")
                return false
            }
            if (isRecording || recordingJob != null || fallbackMediaRecorder != null) {
                Log.w(TAG, "A recording session is already active or being stopped")
                return false
            }
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
                val fos = try {
                    FileOutputStream(outputFile)
                } catch (error: Exception) {
                    try { record.release() } catch (_: Exception) {}
                    throw error
                }
                try {
                    writeWavHeader(fos, 0)
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "AudioRecord não iniciou a captura."
                    }

                    audioRecord = record
                    isUsingFallback = false
                    isRecording = true
                    isPaused = false

                    val shortBuffer = ShortArray(512)
                    val byteBuffer = ByteArray(1024)

                    recordingJob = scope.launch {
                        var lastVisualizerUpdate = 0L
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
                                    for (i in 0 until shortsRead) {
                                        val sample = shortBuffer[i].toInt()
                                        byteBuffer[i * 2] = (sample and 0xFF).toByte()
                                        byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                                    }
                                    val bytesToWrite = shortsRead * 2
                                    fos.write(byteBuffer, 0, bytesToWrite)
                                    totalPcmBytesWritten += bytesToWrite

                                    var maxAmp = 0
                                    for (i in 0 until shortsRead) {
                                        val amplitude = abs(shortBuffer[i].toInt())
                                        if (amplitude > maxAmp) maxAmp = amplitude
                                    }
                                    _currentAmplitude.value = maxAmp

                                    val nowTime = android.os.SystemClock.uptimeMillis()
                                    if (nowTime - lastVisualizerUpdate >= 33L) {
                                        lastVisualizerUpdate = nowTime
                                        val result = frequencyAnalyzer.analyze(shortBuffer, shortsRead)
                                        _frequencyData.value = result.frequencyBands
                                        _waveformData.value = result.waveformPoints
                                        _decibels.value = result.decibels
                                        _peakFrequencyHz.value = result.peakFrequencyHz
                                    }
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
                } catch (error: Exception) {
                    try { fos.close() } catch (_: Exception) {}
                    try { record.stop() } catch (_: Exception) {}
                    try { record.release() } catch (_: Exception) {}
                    outputFile.delete()
                    throw error
                }
            } else {
                try { record?.release() } catch (_: Exception) {}
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
        val fallbackOutputFile = if (outputFile.extension.equals("wav", ignoreCase = true)) {
            File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.m4a")
        } else {
            outputFile
        }
        var recorder: MediaRecorder? = null

        return try {
            fallbackOutputFile.parentFile?.mkdirs()
            fallbackOutputFile.delete()

            val createdRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = createdRecorder

            createdRecorder.apply {
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
            currentOutputFile = fallbackOutputFile
            isUsingFallback = true
            isRecording = true
            isPaused = false

            // Periodic sampler for amplitude and synthetic voice frequency spectrum
            val activeRecorder = createdRecorder
            recordingJob = scope.launch {
                while (isActive && isRecording) {
                    if (!isPaused) {
                        val maxAmp = try { activeRecorder.maxAmplitude } catch (_: Exception) { 0 }
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
            try { recorder?.reset() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            fallbackOutputFile.delete()
            fallbackMediaRecorder = null
            isRecording = false
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

    /**
     * Solicita a parada do gravador e finaliza o arquivo em IO depois que o escritor
     * terminar. O cabeçalho WAV nunca é atualizado enquanto o fluxo ainda está aberto.
     */
    fun stopRecording(): Boolean {
        if (!isRecording && recordingJob == null && fallbackMediaRecorder == null) return false
        if (stopFinalizationJob?.isActive == true) return true

        val wasRecording = isRecording
        isRecording = false
        isPaused = false

        val recordToStop = audioRecord
        val fallbackToStop = fallbackMediaRecorder
        val writerJob = recordingJob
        val outputFile = currentOutputFile

        // AudioRecord.stop() desbloqueia read(); não cancelamos o escritor antes do finally/close.
        try {
            if (recordToStop?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recordToStop.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling AudioRecord stop", e)
        }
        try {
            fallbackToStop?.stop()
        } catch (e: Exception) {
            // MediaRecorder pode lançar quando a gravação ainda não produziu frames.
            Log.w(TAG, "Error stopping MediaRecorder", e)
        }

        stopFinalizationJob = scope.launch {
            try {
                writerJob?.join()
                try { recordToStop?.release() } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord", e)
                }
                try {
                    fallbackToStop?.reset()
                    fallbackToStop?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing MediaRecorder", e)
                }

                if (recordToStop != null && outputFile != null) {
                    try {
                        updateWavHeader(outputFile, totalPcmBytesWritten)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating WAV header", e)
                    }
                }
            } finally {
                if (audioRecord === recordToStop) audioRecord = null
                if (fallbackMediaRecorder === fallbackToStop) fallbackMediaRecorder = null
                if (recordingJob === writerJob) recordingJob = null
                val idle = frequencyAnalyzer.idleResult()
                _frequencyData.value = idle.frequencyBands
                _waveformData.value = idle.waveformPoints
                _decibels.value = -60f
                _currentAmplitude.value = 0
            }
        }
        return wasRecording || writerJob != null || fallbackToStop != null
    }

    /** Aguarda o fechamento efetivo do arquivo antes de permitir cópia ou prévia. */
    suspend fun stopRecordingAndAwait(): Boolean {
        val requested = stopRecording()
        stopFinalizationJob?.join()
        return requested
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

    /**
     * Interrompe uma gravação abandonada e remove o arquivo temporário somente
     * depois que o escritor fechar o fluxo. O arquivo persistente é gerenciado pelo ViewModel.
     */
    fun release() {
        stopRecording()
        scope.launch {
            stopFinalizationJob?.join()
            val file = currentOutputFile ?: return@launch
            try {
                val temporaryDir = File(context.filesDir, "lembretes_audio").canonicalFile
                val source = file.canonicalFile
                if (source.parentFile == temporaryDir) {
                    source.delete()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not remove abandoned temporary recording", error)
            }
        }
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
