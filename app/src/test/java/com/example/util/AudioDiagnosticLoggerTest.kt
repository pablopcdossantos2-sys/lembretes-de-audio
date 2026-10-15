package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioDiagnosticLoggerTest {

    @Before
    fun setUp() {
        AudioDiagnosticLogger.clearLogs()
    }

    @Test
    fun testLogAdditionAndFlow() {
        AudioDiagnosticLogger.log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.PLAYBACK,
            message = "Test playback message",
            details = "Volume 80%"
        )

        val logs = AudioDiagnosticLogger.logsFlow.value
        assertEquals(1, logs.size)
        val entry = logs.first()
        assertEquals("Test playback message", entry.message)
        assertEquals(DiagnosticLevel.INFO, entry.level)
        assertEquals(DiagnosticCategory.PLAYBACK, entry.category)
        assertEquals("Volume 80%", entry.details)
        assertNotNull(entry.formattedTime)
    }

    @Test
    fun testPlaybackStartAndEndLogging() {
        AudioDiagnosticLogger.logPlaybackStart(
            title = "Lembrete Postura",
            uri = "file:///dummy/path.wav",
            isHeadphones = true,
            volumePercent = 85,
            streamType = "STREAM_MUSIC"
        )

        AudioDiagnosticLogger.logPlaybackEnd(
            title = "Lembrete Postura",
            durationMs = 4500L,
            success = true
        )

        val logs = AudioDiagnosticLogger.logsFlow.value
        assertEquals(2, logs.size)
        assertTrue(logs.any { it.level == DiagnosticLevel.SUCCESS })
    }

    @Test
    fun testGetLogsAsFormattedText() {
        AudioDiagnosticLogger.log(
            level = DiagnosticLevel.ERROR,
            category = DiagnosticCategory.ROUTING,
            message = "Headphone disconnected during playback"
        )

        val text = AudioDiagnosticLogger.getLogsAsFormattedText()
        assertTrue(text.contains("DIAGNÓSTICO DE ÁUDIO"))
        assertTrue(text.contains("[ERRO]"))
        assertTrue(text.contains("Headphone disconnected"))
    }
}
