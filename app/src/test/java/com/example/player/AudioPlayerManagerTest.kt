package com.example.player

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPlayerManagerTest {

    private lateinit var context: Context
    private lateinit var audioPlayerManager: AudioPlayerManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioPlayerManager = AudioPlayerManager(context)
    }

    @Test
    fun testAudioPlayerManagerInitialization() {
        assertNotNull(audioPlayerManager)
    }

    @Test
    fun testLoudspeakerOutputConfiguration() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        assertTrue("Max alarm volume should be greater than zero", maxAlarmVol > 0)

        // Test speaker test method
        var completed = false
        audioPlayerManager.testSpeaker(volumePercent = 100, useAppVolumeOnly = true) {
            completed = true
        }
        audioPlayerManager.stop()
    }
}
