package com.example.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseSeedTest {

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
    fun `seed adds missing profiles without duplicating existing audios`() = runBlocking {
        database.audioDao().insertAudio(
            AudioItemEntity(
                uriString = "tts:custom",
                label = "Áudio personalizado",
                audioType = "TEXT_TO_SPEECH"
            )
        )

        AppDatabase.ensureInitialDataSeeded(database)

        assertEquals(1, database.audioDao().getAllAudios().size)
        assertEquals(1, database.profileDao().getAllProfilesWithRests().size)
    }

    @Test
    fun `seed adds missing audios without duplicating existing profiles`() = runBlocking {
        database.profileDao().upsertProfileWithRests(
            ProfileEntity(name = "Perfil personalizado"),
            emptyList()
        )

        AppDatabase.ensureInitialDataSeeded(database)

        assertEquals(1, database.profileDao().getAllProfilesWithRests().size)
        assertEquals(1, database.audioDao().getAllAudios().size)
    } 
    @Test
    fun `fresh database receives only one demo profile and one demo audio`() = runBlocking {
        AppDatabase.ensureInitialDataSeeded(database)

        val profiles = database.profileDao().getAllProfilesWithRests()
        val audios = database.audioDao().getAllAudios()

        assertEquals(1, profiles.size)
        assertEquals(1, audios.size)
        assertEquals("Perfil de Demonstração", profiles.single().profile.name)
        assertEquals(15, profiles.single().profile.intervalMinutes)
        assertEquals("Hora de Beber Água", audios.single().label)
    }

}
