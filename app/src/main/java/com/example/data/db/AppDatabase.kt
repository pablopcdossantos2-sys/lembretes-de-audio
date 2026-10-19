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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audio_reminders.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        database.ensureInitialDataSeeded()
                    }
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
                        com.example.service.AudioReminderService.startOrRefresh(context)
                        com.example.worker.ReminderWorkScheduler.rescheduleNextReminder(context)
                        com.example.util.AudioDiagnosticLogger.log(
                            level = com.example.util.DiagnosticLevel.INFO,
                            category = com.example.util.DiagnosticCategory.SERVICE,
                            message = "Dados padrão do app inicializados com sucesso",
                            details = "3 perfis e 3 lembretes de áudio configurados com arquivos WAV reais"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        suspend fun populateInitialData(database: AppDatabase, context: Context? = null) {
            val profileDao = database.profileDao()

            val bundledFiles = if (context != null) {
                try {
                    com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(context)
                } catch (_: Throwable) {
                    emptyList()
                }
            } else emptyList()

            // Perfil Comercial (Segunda a Sexta, 08:00 às 18:00, a cada 15 min - sem descansos forçados)
            val commercial = ProfileEntity(
                name = "Horário Comercial",
                isEnabled = true,
                priority = 10,
                daysOfWeekCsv = "1,2,3,4,5",
                startHour = 8,
                startMinute = 0,
                endHour = 18,
                endMinute = 0,
                intervalMinutes = 15,
                playbackOrder = "SEQUENTIAL",
                resumePolicy = "NEXT_GRID_MARK",
                fadeInOverrideEnabled = false,
                fadeInDurationSeconds = 2.0f
            )
            profileDao.upsertProfileWithRests(commercial, emptyList())

            // Perfil Final de Semana (Sábado e Domingo, 09:00 às 21:00, a cada 30 min - sem descansos forçados)
            val weekend = ProfileEntity(
                name = "Fim de Semana",
                isEnabled = true,
                priority = 5,
                daysOfWeekCsv = "6,7",
                startHour = 9,
                startMinute = 0,
                endHour = 21,
                endMinute = 0,
                intervalMinutes = 30,
                playbackOrder = "RANDOM",
                resumePolicy = "NEXT_GRID_MARK",
                fadeInOverrideEnabled = false,
                fadeInDurationSeconds = 2.0f
            )
            profileDao.upsertProfileWithRests(weekend, emptyList())

            // Perfil Rotina Diária (Todos os dias, 00:00 às 23:59, a cada 15 min, garantindo funcionamento contínuo)
            val daily = ProfileEntity(
                name = "Rotina Diária",
                isEnabled = true,
                priority = 3,
                daysOfWeekCsv = "1,2,3,4,5,6,7",
                startHour = 0,
                startMinute = 0,
                endHour = 23,
                endMinute = 59,
                intervalMinutes = 15,
                playbackOrder = "SEQUENTIAL",
                resumePolicy = "NEXT_GRID_MARK",
                fadeInOverrideEnabled = false,
                fadeInDurationSeconds = 2.0f
            )
            profileDao.upsertProfileWithRests(daily, emptyList())

            // Adiciona áudios iniciais reais (arquivos WAV gerados com melodia harmônica)
            val audioDao = database.audioDao()
            val uriWater = if (bundledFiles.isNotEmpty() && bundledFiles[0].exists()) {
                "file://${bundledFiles[0].absolutePath}"
            } else {
                "tts:beba_agua"
            }
            val uriPosture = if (bundledFiles.size > 1 && bundledFiles[1].exists()) {
                "file://${bundledFiles[1].absolutePath}"
            } else {
                "tts:postura_alongamento"
            }
            val uriBreath = if (bundledFiles.size > 2 && bundledFiles[2].exists()) {
                "file://${bundledFiles[2].absolutePath}"
            } else {
                "tts:respiracao"
            }

            val demoAudio1 = AudioItemEntity(
                uriString = uriWater,
                label = "Hora de Beber Água",
                weight = 4,
                volumePercent = 100,
                isActive = true,
                audioType = if (uriWater.startsWith("file:")) "AUDIO_FILE" else "TEXT_TO_SPEECH",
                ttsText = "Lembrete: beba um copo d'água e mantenha-se hidratado."
            )
            val demoAudio2 = AudioItemEntity(
                uriString = uriPosture,
                label = "Alongamento e Postura",
                weight = 3,
                volumePercent = 100,
                isActive = true,
                audioType = if (uriPosture.startsWith("file:")) "AUDIO_FILE" else "TEXT_TO_SPEECH",
                ttsText = "Ajuste sua postura e faça uma pequena pausa para alongar as costas."
            )
            val demoAudio3 = AudioItemEntity(
                uriString = uriBreath,
                label = "Pausa para Respiração",
                weight = 2,
                volumePercent = 90,
                isActive = true,
                audioType = if (uriBreath.startsWith("file:")) "AUDIO_FILE" else "TEXT_TO_SPEECH",
                ttsText = "Inspire profundamente e relaxe os ombros por alguns segundos."
            )
            audioDao.insertAudios(listOf(demoAudio1, demoAudio2, demoAudio3))
        }
        }
    }

