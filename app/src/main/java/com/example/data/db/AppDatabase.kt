package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        AudioItemEntity::class,
        ProfileEntity::class,
        RestingPeriodEntity::class,
        ReminderEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun audioDao(): AudioDao
    abstract fun profileDao(): ProfileDao
    abstract fun reminderDao(): ReminderDao

    suspend fun ensureInitialDataSeeded(context: Context? = null) {
        Companion.ensureInitialDataSeeded(this, context)
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `profiles` ADD COLUMN `fadeInOverrideEnabled` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE `profiles` ADD COLUMN `fadeInDurationSeconds` REAL NOT NULL DEFAULT 2.0")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE `profiles` ADD COLUMN `resumePolicy` TEXT NOT NULL DEFAULT 'NEXT_GRID_MARK'")
                } catch (_: Exception) {}
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "audio_reminders.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(DatabaseCallback(appContext))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val appContext: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getDatabase(appContext)
                    ensureInitialDataSeeded(database, appContext)
                }
            }
        }

        suspend fun ensureInitialDataSeeded(database: AppDatabase, context: Context? = null) {
            val profileDao = database.profileDao()
            val audioDao = database.audioDao()

            if (context != null) {
                try {
                    val files = com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(context)
                    val currentAudios = audioDao.getAllAudios()
                    for (aud in currentAudios) {
                        if (aud.uriString == "tts:beba_agua" && files.isNotEmpty()) {
                            audioDao.updateAudio(aud.copy(uriString = "file://${files[0].absolutePath}", audioType = "AUDIO_FILE"))
                        } else if (aud.uriString == "tts:postura_alongamento" && files.size > 1) {
                            audioDao.updateAudio(aud.copy(uriString = "file://${files[1].absolutePath}", audioType = "AUDIO_FILE"))
                        } else if (aud.uriString == "tts:respiracao" && files.size > 2) {
                            audioDao.updateAudio(aud.copy(uriString = "file://${files[2].absolutePath}", audioType = "AUDIO_FILE"))
                        }
                    }
                } catch (_: Throwable) {}
            }

            val existingProfiles = profileDao.getAllProfilesWithRests()
            val existingAudios = audioDao.getAllAudios()

            if (existingProfiles.isEmpty() || existingAudios.isEmpty()) {
                populateInitialData(database, context)
                if (context != null) {
                    try {
                        // A inicialização do banco pode acontecer durante BOOT_COMPLETED.
                        // Não inicie um foreground service a partir deste caminho de inicialização.
                        com.example.worker.ReminderWorkScheduler.rescheduleNextReminder(context)
                        com.example.util.AudioDiagnosticLogger.log(
                            level = com.example.util.DiagnosticLevel.INFO,
                            category = com.example.util.DiagnosticCategory.SERVICE,
                            message = "Dados padrão do app inicializados com sucesso",
                            details = "Um perfil de demonstração e um lembrete de áudio inicializados"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        suspend fun populateInitialData(database: AppDatabase, context: Context? = null) {
            val profileDao = database.profileDao()
            val audioDao = database.audioDao()

            val bundledFiles = if (context != null) {
                try {
                    com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(context)
                } catch (_: Throwable) {
                    emptyList()
                }
            } else emptyList()

            // Preserva áudios existentes; em uma instalação nova, cria apenas um exemplo.
            val existingAudios = audioDao.getAllAudios()
            if (existingAudios.isEmpty()) {
                val demoAudioUri = if (bundledFiles.isNotEmpty() && bundledFiles[0].exists()) {
                    "file://${bundledFiles[0].absolutePath}"
                } else {
                    "tts:beba_agua"
                }
                val demoAudio = AudioItemEntity(
                    uriString = demoAudioUri,
                    label = "Hora de Beber Água",
                    weight = 4,
                    volumePercent = 100,
                    isActive = true,
                    audioType = if (demoAudioUri.startsWith("file:")) "AUDIO_FILE" else "TEXT_TO_SPEECH",
                    ttsText = "Lembrete: beba um copo d'água e mantenha-se hidratado."
                )
                audioDao.insertAudio(demoAudio)
            }

            // Não substitui nem apaga perfis existentes.
            if (profileDao.getAllProfilesWithRests().isEmpty()) {
                val demoProfile = ProfileEntity(
                    name = "Perfil de Demonstração",
                    isEnabled = true,
                    priority = 10,
                    daysOfWeekCsv = "1,2,3,4,5,6,7",
                    startHour = 8,
                    startMinute = 0,
                    endHour = 18,
                    endMinute = 0,
                    intervalMinutes = 15,
                    playbackOrder = "SEQUENTIAL",
                    specificAudioId = null,
                    audioIdsCsv = "",
                    resumePolicy = "NEXT_GRID_MARK",
                    fadeInOverrideEnabled = false,
                    fadeInDurationSeconds = 2.0f
                )
                profileDao.upsertProfileWithRests(demoProfile, emptyList())
            }
        }
    }
}
