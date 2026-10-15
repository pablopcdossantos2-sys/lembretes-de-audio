package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioFrequencyAnalyzerTest {

    @Test
    fun testSilenceProducesLowEnergy() {
        val analyzer = AudioFrequencyAnalyzer(sampleRate = 44100, fftSize = 512, numBands = 36)
        val silence = ShortArray(512) { 0 }
        val result = analyzer.analyze(silence, silence.size)

        assertEquals(36, result.frequencyBands.size)
        assertEquals(48, result.waveformPoints.size)
        assertTrue("Silence decibels should be close to -60dB, was ${result.decibels}", result.decibels <= -50f)
        for (band in result.frequencyBands) {
            assertTrue("Band energy during silence should be minimal, was $band", band <= 0.15f)
        }
    }

    @Test
    fun testSineWaveDetectsCorrectFrequencyRange() {
        val sampleRate = 44100
        val targetFreq = 440.0 // Standard pitch 440 Hz (vocal range)
        val analyzer = AudioFrequencyAnalyzer(sampleRate = sampleRate, fftSize = 512, numBands = 36)

        val sineWave = ShortArray(512) { i ->
            (sin(2.0 * PI * targetFreq * i / sampleRate) * 20000).toInt().toShort()
        }

        val result = analyzer.analyze(sineWave, sineWave.size)

        assertTrue("Decibels for loud sine wave should be higher than -30dB, was ${result.decibels}", result.decibels > -30f)
        assertTrue("Peak frequency should be close to 440 Hz, was ${result.peakFrequencyHz}", result.peakFrequencyHz in 350f..550f)

        var hasActiveBand = false
        for (band in result.frequencyBands) {
            if (band > 0.1f) hasActiveBand = true
        }
        assertTrue("Should have active frequency bands for 440 Hz tone", hasActiveBand)
    }

    @Test
    fun testFallbackSimulationProducesValidSpectrum() {
        val analyzer = AudioFrequencyAnalyzer(sampleRate = 44100, fftSize = 512, numBands = 36)
        val result = analyzer.simulateFromAmplitude(0.75f)

        assertEquals(36, result.frequencyBands.size)
        assertEquals(48, result.waveformPoints.size)
        assertTrue("Decibels should reflect amplitude", result.decibels > -30f)
        assertTrue("Peak frequency should be in audio range", result.peakFrequencyHz in 200f..3000f)
    }
}
