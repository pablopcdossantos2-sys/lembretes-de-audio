package com.example.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance audio frequency spectrum analyzer.
 * Processes 16-bit PCM audio samples, applies Hann windowing,
 * computes Fast Fourier Transform (FFT), and groups results into
 * calibrated musical and vocal frequency bands.
 */
class AudioFrequencyAnalyzer(
    val sampleRate: Int = 44100,
    val fftSize: Int = 512,
    val numBands: Int = 36
) {
    // Ring buffer of recent PCM samples for FFT
    private val ringBuffer = FloatArray(fftSize)
    private var ringPos = 0
    private var totalSamplesReceived = 0

    // Precomputed Hann window weights to minimize spectral leakage
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    // FFT working buffers
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2)

    // Smoothed frequency band levels (decay filter for smooth UI animation)
    private val smoothedBands = FloatArray(numBands)

    // Normalized waveform points for oscilloscope view
    private val waveformPoints = FloatArray(48)

    // Logarithmic frequency band boundaries (start and end bin index for each band)
    private val bandBinRanges = Array(numBands) { 0..0 }

    init {
        // Map 0Hz .. (sampleRate / 2) Hz into numBands log-distributed frequency ranges
        val minFreq = 40.0 // 40 Hz (Sub-bass)
        val maxFreq = 11000.0 // 11 kHz (Presence / Air)
        val binWidth = sampleRate.toDouble() / fftSize // ~86.1 Hz per bin

        for (b in 0 until numBands) {
            val fLow = minFreq * Math.pow(maxFreq / minFreq, b.toDouble() / numBands)
            val fHigh = minFreq * Math.pow(maxFreq / minFreq, (b + 1).toDouble() / numBands)

            val binStart = (fLow / binWidth).toInt().coerceIn(1, (fftSize / 2) - 1)
            val binEnd = (fHigh / binWidth).toInt().coerceIn(binStart, (fftSize / 2) - 1)
            bandBinRanges[b] = binStart..binEnd
        }
    }

    data class AnalysisResult(
        val frequencyBands: FloatArray, // Normalized 0.0f .. 1.0f for each band
        val waveformPoints: FloatArray, // Normalized -1.0f .. 1.0f for oscilloscope
        val decibels: Float,            // RMS dB level (-60dB .. 0dB)
        val peakFrequencyHz: Float      // Dominant frequency in Hz
    )

    /**
     * Ingest new 16-bit PCM samples and compute real-time frequency data.
     */
    @Synchronized
    fun analyze(pcmSamples: ShortArray, length: Int): AnalysisResult {
        if (length <= 0) {
            return idleResult()
        }

        // 1. Calculate RMS decibels and fill ring buffer
        var sumSquares = 0.0
        val waveformStride = maxOf(1, length / waveformPoints.size)
        var waveIdx = 0

        for (i in 0 until length) {
            val sampleVal = pcmSamples[i].toFloat() / 32768.0f
            sumSquares += sampleVal * sampleVal

            ringBuffer[ringPos] = sampleVal
            ringPos = (ringPos + 1) % fftSize
            totalSamplesReceived++

            if (i % waveformStride == 0 && waveIdx < waveformPoints.size) {
                // Smooth waveform points
                waveformPoints[waveIdx] = waveformPoints[waveIdx] * 0.3f + sampleVal * 0.7f
                waveIdx++
            }
        }

        val rms = sqrt(sumSquares / length).toFloat()
        val decibels = if (rms > 1e-4f) {
            (20.0f * log10(rms)).coerceIn(-60.0f, 0.0f)
        } else {
            -60.0f
        }

        // 2. Prepare windowed data for FFT from ring buffer
        for (i in 0 until fftSize) {
            val idx = (ringPos + i) % fftSize
            real[i] = ringBuffer[idx] * hannWindow[i]
            imag[i] = 0.0f
        }

        // 3. Perform Fast Fourier Transform (Cooley-Tukey Radix-2)
        fft(real, imag, fftSize)

        // 4. Calculate magnitudes for positive frequencies (bins 0 .. fftSize/2 - 1)
        var maxMag = 0.0f
        var peakBin = 0
        for (i in 0 until fftSize / 2) {
            val r = real[i]
            val im = imag[i]
            val mag = sqrt(r * r + im * im)
            magnitudes[i] = mag
            if (mag > maxMag && i > 0) {
                maxMag = mag
                peakBin = i
            }
        }

        val binWidth = sampleRate.toFloat() / fftSize
        val peakFrequencyHz = (peakBin * binWidth).coerceIn(0f, sampleRate / 2f)

        // 5. Aggregate FFT bins into calibrated frequency bands
        val targetBands = FloatArray(numBands)
        val maxExpectedMag = 12.0f // Calibration ceiling for normalized voice

        for (b in 0 until numBands) {
            val range = bandBinRanges[b]
            var bandSum = 0.0f
            var count = 0
            for (bin in range) {
                bandSum += magnitudes[bin]
                count++
            }
            val avgMag = if (count > 0) bandSum / count else 0.0f

            // Equal loudness / high frequency boost curve (human ear is less sensitive to treble)
            val freqBoost = 1.0f + (b.toFloat() / numBands) * 1.5f
            val normalized = (avgMag * freqBoost / maxExpectedMag).coerceIn(0.0f, 1.0f)
            targetBands[b] = normalized

            // Smooth with fast attack, gentle decay
            val prev = smoothedBands[b]
            if (normalized > prev) {
                smoothedBands[b] = prev * 0.25f + normalized * 0.75f // Fast attack
            } else {
                smoothedBands[b] = prev * 0.82f + normalized * 0.18f // Smooth decay
            }
        }

        return AnalysisResult(
            frequencyBands = smoothedBands.copyOf(),
            waveformPoints = waveformPoints.copyOf(),
            decibels = decibels,
            peakFrequencyHz = peakFrequencyHz
        )
    }

    /**
     * Simulates frequency data based on a scalar amplitude level (fallback mode).
     */
    fun simulateFromAmplitude(normalizedAmp: Float): AnalysisResult {
        val clamped = normalizedAmp.coerceIn(0.02f, 1.0f)
        val resultBands = FloatArray(numBands)

        for (i in 0 until numBands) {
            // Vocal frequency curve: highest in mid bands (8..22), tapering off at extremes
            val vocalWeight = 1.0f - abs((i - (numBands * 0.42f)) / (numBands * 0.5f)).coerceIn(0f, 0.9f)
            val flutter = (sin((i * 1.3f) + (System.currentTimeMillis() * 0.008f)) * 0.15f).toFloat()
            val bandVal = (clamped * vocalWeight + flutter).coerceIn(0.04f, 1.0f)

            smoothedBands[i] = smoothedBands[i] * 0.3f + bandVal * 0.7f
            resultBands[i] = smoothedBands[i]
        }

        val wave = FloatArray(waveformPoints.size) { idx ->
            (sin((idx * 0.4f) + (System.currentTimeMillis() * 0.012f)) * clamped).toFloat()
        }

        return AnalysisResult(
            frequencyBands = resultBands,
            waveformPoints = wave,
            decibels = (-60.0f + clamped * 54.0f).coerceIn(-60.0f, 0.0f),
            peakFrequencyHz = 350f + clamped * 1200f
        )
    }

    fun idleResult(): AnalysisResult {
        for (i in 0 until numBands) {
            smoothedBands[i] = (smoothedBands[i] * 0.85f).coerceAtLeast(0.03f)
        }
        for (i in waveformPoints.indices) {
            waveformPoints[i] = waveformPoints[i] * 0.85f
        }
        return AnalysisResult(
            frequencyBands = smoothedBands.copyOf(),
            waveformPoints = waveformPoints.copyOf(),
            decibels = -60.0f,
            peakFrequencyHz = 0.0f
        )
    }

    fun reset() {
        ringBuffer.fill(0f)
        ringPos = 0
        totalSamplesReceived = 0
        smoothedBands.fill(0.03f)
        waveformPoints.fill(0f)
    }

    /**
     * In-place Radix-2 Cooley-Tukey Fast Fourier Transform.
     * Computes real and imaginary components for n samples where n is a power of 2.
     */
    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey butterflies
        var l = 2
        while (l <= n) {
            val halfL = l shr 1
            val angle = -2.0 * PI / l
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (m in 0 until halfL) {
                    val idx1 = i + m
                    val idx2 = idx1 + halfL

                    val uR = real[idx1]
                    val uI = imag[idx1]

                    val vR = real[idx2] * wR - imag[idx2] * wI
                    val vI = real[idx2] * wI + imag[idx2] * wR

                    real[idx1] = uR + vR
                    imag[idx1] = uI + vI

                    real[idx2] = uR - vR
                    imag[idx2] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += l
            }
            l = l shl 1
        }
    }
}
