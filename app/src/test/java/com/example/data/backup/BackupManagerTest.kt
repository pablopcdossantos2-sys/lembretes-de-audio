package com.example.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.ReminderEntity
import com.example.data.db.RestingPeriodEntity
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var prefs: UserPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = UserPreferencesRepository(context)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test
    fun `export and import preserve reminder profile and audio references`() = runBlocking {
        val audioId = sourceDb.audioDao().insertAudio(
            AudioItemEntity(
                uriString = "tts:backup_test",
                label = "Áudio de teste",
                audioType = "TEXT_TO_SPEECH",
                ttsText = "Texto de teste"
            )
        )
        val profileId = sourceDb.profileDao().upsertProfileWithRests(
            ProfileEntity(
                name = "Perfil de teste",
                specificAudioId = audioId,
                audioIdsCsv = audioId.toString()
            ),
            listOf(
                RestingPeriodEntity(
                    label = "Pausa",
                    startHour = 12,
                    startMinute = 0,
                    endHour = 13,
                    endMinute = 0
                )
            )
        )
        sourceDb.reminderDao().insertReminder(
            ReminderEntity(
                profileId = profileId,
                title = "Lembrete de teste",
                text = "Texto do lembrete",
                audioUri = "tts:backup_test"
            )
        )

        // Pre-existing rows force the restore to generate different IDs.
        targetDb.audioDao().insertAudio(
            AudioItemEntity(uriString = "tts:existing", label = "Já existente", audioType = "TEXT_TO_SPEECH")
        )
        targetDb.profileDao().upsertProfileWithRests(
            ProfileEntity(name = "Perfil já existente"),
            emptyList()
        )

        val output = ByteArrayOutputStream()
        assertTrue(BackupManager(context, sourceDb, prefs).exportConfig(output))

        val result = BackupManager(context, targetDb, prefs).importConfig(
            ByteArrayInputStream(output.toByteArray())
        )

        assertTrue(result.message, result.success)
        assertEquals(1, result.importedAudiosCount)
        assertEquals(1, result.importedProfilesCount)

        val importedAudio = targetDb.audioDao().getAllAudios().single { it.label == "Áudio de teste" }
        val importedProfile = targetDb.profileDao().getAllProfilesWithRests()
            .single { it.profile.name == "Perfil de teste" }
        val importedReminder = targetDb.reminderDao().getAllReminders().single()

        assertEquals(importedAudio.id, importedProfile.profile.specificAudioId)
        assertEquals(importedAudio.id.toString(), importedProfile.profile.audioIdsCsv)
        assertEquals(importedProfile.profile.id, importedReminder.profileId)
        assertEquals("Lembrete de teste", importedReminder.title)
        assertEquals(1, importedProfile.restingPeriods.size)
    }

    @Test
    fun `invalid reminder entry rolls back all imported database rows`() = runBlocking {
        val audioId = sourceDb.audioDao().insertAudio(
            AudioItemEntity(
                uriString = "tts:rollback_test",
                label = "Áudio de teste",
                audioType = "TEXT_TO_SPEECH"
            )
        )
        sourceDb.profileDao().upsertProfileWithRests(
            ProfileEntity(name = "Perfil de teste", specificAudioId = audioId),
            emptyList()
        )

        val output = ByteArrayOutputStream()
        assertTrue(BackupManager(context, sourceDb, prefs).exportConfig(output))
        val corrupted = JSONObject(output.toString(Charsets.UTF_8.name()))
            .put("reminders", JSONArray().put("isso não é um objeto de lembrete"))

        val result = BackupManager(context, targetDb, prefs).importConfig(
            ByteArrayInputStream(corrupted.toString().toByteArray(Charsets.UTF_8))
        )

        assertFalse(result.success)
        assertTrue(targetDb.audioDao().getAllAudios().isEmpty())
        assertTrue(targetDb.profileDao().getAllProfilesWithRests().isEmpty())
        assertTrue(targetDb.reminderDao().getAllReminders().isEmpty())
    }

    @Test
    fun `import disables reminder when its local audio file is missing`() = runBlocking {
        val missingFile = File(context.filesDir, "audio-that-does-not-exist.wav")
        sourceDb.reminderDao().insertReminder(
            ReminderEntity(
                title = "Lembrete com áudio ausente",
                audioFilePath = missingFile.absolutePath,
                isEnabled = true
            )
        )

        val output = ByteArrayOutputStream()
        assertTrue(BackupManager(context, sourceDb, prefs).exportConfig(output))

        val result = BackupManager(context, targetDb, prefs).importConfig(
            ByteArrayInputStream(output.toByteArray())
        )

        assertTrue(result.message, result.success)
        val importedReminder = targetDb.reminderDao().getAllReminders().single()
        assertFalse("Lembrete sem o arquivo local não deve ser ativado", importedReminder.isEnabled)
    }

    @Test
    fun `export and import restore recorded audio bytes in internal storage`() = runBlocking {
        val testAudioFile = File(context.filesDir, "test_audio_record.wav").apply {
            writeBytes("RIFF_WAV_SIMULATED_AUDIO_BYTES_FOR_BACKUP_TEST".toByteArray())
        }
        val audioId = sourceDb.audioDao().insertAudio(
            AudioItemEntity(
                uriString = "file://${testAudioFile.absolutePath}",
                label = "Gravação de Voz",
                audioType = "AUDIO_FILE",
                isActive = true
            )
        )

        val output = ByteArrayOutputStream()
        assertTrue(BackupManager(context, sourceDb, prefs).exportConfig(output))

        val exportedJson = JSONObject(output.toString(Charsets.UTF_8.name()))
        val audiosArr = exportedJson.getJSONArray("audios")
        assertEquals(1, audiosArr.length())
        assertTrue(audiosArr.getJSONObject(0).has("audioBase64"))

        val result = BackupManager(context, targetDb, prefs).importConfig(
            ByteArrayInputStream(output.toByteArray())
        )
        assertTrue(result.message, result.success)

        val importedAudio = targetDb.audioDao().getAllAudios().single()
        assertTrue("Áudio restaurado deve ser mantido ativo", importedAudio.isActive)
        assertTrue("URI do áudio restaurado deve apontar para arquivo válido", importedAudio.uriString.startsWith("file://"))
        val restoredFile = File(importedAudio.uriString.removePrefix("file://"))
        assertTrue("Arquivo físico restaurado deve existir", restoredFile.exists())
        assertEquals("RIFF_WAV_SIMULATED_AUDIO_BYTES_FOR_BACKUP_TEST", restoredFile.readText())
    }
}
