package com.example.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ReminderFrequencyUpdater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderFrequencyUpdaterTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `frequency update synchronizes linked profiles and reminder records only`() = runBlocking {
        val audioUri = "file:///demo/agua.wav"
        val audioId = database.audioDao().insertAudio(
            AudioItemEntity(
                uriString = audioUri,
                label = "Hora de Beber Água",
                audioType = "AUDIO_FILE"
            )
        )

        val linkedProfileId = database.profileDao().upsertProfileWithRests(
            ProfileEntity(
                name = "Perfil do lembrete",
                intervalMinutes = 15,
                playbackOrder = "SPECIFIC",
                specificAudioId = audioId
            ),
            emptyList()
        )
        val sharedProfileId = database.profileDao().upsertProfileWithRests(
            ProfileEntity(
                name = "Perfil compartilhado",
                intervalMinutes = 30
            ),
            emptyList()
        )
        val unrelatedProfileId = database.profileDao().upsertProfileWithRests(
            ProfileEntity(
                name = "Outro lembrete",
                intervalMinutes = 45,
                playbackOrder = "SPECIFIC",
                specificAudioId = audioId + 999
            ),
            emptyList()
        )
        val reminderId = database.reminderDao().insertReminder(
            ReminderEntity(
                profileId = linkedProfileId,
                title = "Hora de Beber Água",
                audioUri = audioUri,
                intervalMinutes = 15
            )
        )

        val result = ReminderFrequencyUpdater.update(
            database = database,
            audioId = audioId,
            audioUri = audioUri,
            requestedIntervalMinutes = 60
        )

        assertTrue(result.applied)
        assertEquals(60, result.intervalMinutes)
        assertEquals(2, result.updatedProfiles)
        assertEquals(1, result.updatedReminders)
        assertEquals(60, database.profileDao().getProfileWithRestsById(linkedProfileId)?.profile?.intervalMinutes)
        assertEquals(60, database.profileDao().getProfileWithRestsById(sharedProfileId)?.profile?.intervalMinutes)
        assertEquals(45, database.profileDao().getProfileWithRestsById(unrelatedProfileId)?.profile?.intervalMinutes)
        assertEquals(60, database.reminderDao().getReminderById(reminderId)?.intervalMinutes)
    }

    @Test
    fun `frequency update clamps unsupported interval values`() = runBlocking {
        val audioUri = "file:///demo/respiracao.wav"
        val audioId = database.audioDao().insertAudio(
            AudioItemEntity(uriString = audioUri, label = "Respiração", audioType = "AUDIO_FILE")
        )
        database.profileDao().upsertProfileWithRests(
            ProfileEntity(name = "Perfil", intervalMinutes = 15),
            emptyList()
        )

        val result = ReminderFrequencyUpdater.update(
            database = database,
            audioId = audioId,
            audioUri = audioUri,
            requestedIntervalMinutes = 999
        )

        assertTrue(result.applied)
        assertEquals(120, result.intervalMinutes)
        assertEquals(120, database.profileDao().getAllProfilesWithRests().single().profile.intervalMinutes)
    }
}
