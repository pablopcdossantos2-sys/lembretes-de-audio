package com.example.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class AudioTrimmerUtilTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFormatTimeMs() {
        assertEquals("00:00.0", AudioTrimmerUtil.formatTimeMs(0L))
        assertEquals("00:03.4", AudioTrimmerUtil.formatTimeMs(3400L))
        assertEquals("01:15.8", AudioTrimmerUtil.formatTimeMs(75800L))
    }

    @Test
    fun testWavTrimmingAndSilenceDetection() {
        val sampleRate = 44100
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val byteRate = sampleRate * channels * bytesPerSample

        // 2 seconds total: 0.5s silence, 1.0s tone (440Hz), 0.5s silence
        val totalSamples = sampleRate * 2
        val toneStart = sampleRate / 2 // 0.5s
        val toneEnd = sampleRate + (sampleRate / 2) // 1.5s

        val samples = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            if (i in toneStart until toneEnd) {
                samples[i] = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000).toInt().toShort()
            } else {
                samples[i] = 0 // Silence
            }
        }

        val testWav = tempFolder.newFile("test_source.wav")
        writeTestWav(testWav, samples, sampleRate, channels)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Analyze WAV
        val info = runBlockingRobolectric {
            AudioTrimmerUtil.analyzeAudioFile(context, testWav.toURI().toString())
        }

        assertNotNull(info)
        assertEquals(2000L, info.durationMs)
        assertTrue("Waveform points should be populated", info.waveformPoints.isNotEmpty())
        assertTrue("Suggested start should be around 400-550ms", info.suggestedStartMs in 350L..600L)
        assertTrue("Suggested end should be around 1400-1650ms", info.suggestedEndMs in 1400L..1650L)

        // 2. Trim WAV from 500ms to 1500ms
        val trimmedFile = tempFolder.newFile("test_trimmed.wav")
        val success = runBlockingRobolectric {
            AudioTrimmerUtil.trimAudio(
                context = context,
                sourceUriString = testWav.toURI().toString(),
                startMs = 500L,
                endMs = 1500L,
                outputFile = trimmedFile
            )
        }

        assertTrue("Trim operation should succeed", success)
        assertTrue("Trimmed file should exist", trimmedFile.exists())

        // 1 second of mono 16-bit 44.1kHz audio = 88200 bytes + 44 bytes header = 88244 bytes
        val expectedLength = 44L + (1000L * byteRate / 1000L)
        assertEquals(expectedLength, trimmedFile.length())
    }

    private fun writeTestWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val pcmDataSize = samples.size * 2L
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
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
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xff).toByte()
        header[41] = ((pcmDataSize shr 8) and 0xff).toByte()
        header[42] = ((pcmDataSize shr 16) and 0xff).toByte()
        header[43] = ((pcmDataSize shr 24) and 0xff).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                byteBuffer.putShort(s)
            }
            fos.write(byteBuffer.array())
        }
    }

    private fun <T> runBlockingRobolectric(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
