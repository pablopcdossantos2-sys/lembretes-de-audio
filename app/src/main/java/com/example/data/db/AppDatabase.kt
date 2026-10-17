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
    version = 1,
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
            val existingProfiles = profileDao.getAllProfilesWithRests()
            val existingAudios = audioDao.getAllAudios()

            if (existingProfiles.isEmpty() || existingAudios.isEmpty()) {
                populateInitialData(database)
                if (context != null) {
                    try {
                        com.example.service.AudioReminderService.startOrRefresh(context)
                        com.example.worker.ReminderWorkScheduler.rescheduleNextReminder(context)
                        com.example.util.AudioDiagnosticLogger.log(
                            level = com.example.util.DiagnosticLevel.INFO,
                            category = com.example.util.DiagnosticCategory.SERVICE,
                            message = "Dados padrão do app inicializados com sucesso",
                            details = "3 perfis e 3 lembretes de áudio configurados"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        suspend fun populateInitialData(database: AppDatabase) {
            val profileDao = database.profileDao()

                // Perfil Comercial (Segunda a Sexta, 08:00 às 18:00, a cada 15 min com descanso das 12:00 às 13:00)
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
                val commercialRests = listOf(
                    RestingPeriodEntity(
                        label = "Almoço",
                        startHour = 12,
                        startMinute = 0,
                        endHour = 13,
                        endMinute = 0
                    )
                )
                profileDao.upsertProfileWithRests(commercial, commercialRests)

                // Perfil Final de Semana (Sábado e Domingo, 09:00 às 21:00, a cada 30 min com descanso das 13:00 às 14:00)
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
                val weekendRests = listOf(
                    RestingPeriodEntity(
                        label = "Descanso / Almoço",
                        startHour = 13,
                        startMinute = 0,
                        endHour = 14,
                        endMinute = 0
                    )
                )
                profileDao.upsertProfileWithRests(weekend, weekendRests)

                // Perfil Rotina Diária (Todos os dias, 07:00 às 23:00, a cada 15 min, garantindo funcionamento contínuo)
                val daily = ProfileEntity(
                    name = "Rotina Diária",
                    isEnabled = true,
                    priority = 3,
                    daysOfWeekCsv = "1,2,3,4,5,6,7",
                    startHour = 7,
                    startMinute = 0,
                    endHour = 23,
                    endMinute = 0,
                    intervalMinutes = 15,
                    playbackOrder = "SEQUENTIAL",
                    resumePolicy = "NEXT_GRID_MARK",
                    fadeInOverrideEnabled = false,
                    fadeInDurationSeconds = 2.0f
                )
                profileDao.upsertProfileWithRests(daily, emptyList())

                // Adiciona áudios iniciais de demonstração (TTS para funcionar imediatamente sem arquivos locais)
                val audioDao = database.audioDao()
                val demoAudio1 = AudioItemEntity(
                    uriString = "tts:beba_agua",
                    label = "Hora de Beber Água",
                    weight = 4,
                    volumePercent = 100,
                    isActive = true,
                    audioType = "TEXT_TO_SPEECH",
                    ttsText = "Lembrete: beba um copo d'água e mantenha-se hidratado."
                )
                val demoAudio2 = AudioItemEntity(
                    uriString = "tts:postura_alongamento",
                    label = "Alongamento e Postura",
                    weight = 3,
                    volumePercent = 100,
                    isActive = true,
                    audioType = "TEXT_TO_SPEECH",
                    ttsText = "Ajuste sua postura e faça uma pequena pausa para alongar as costas."
                )
                val demoAudio3 = AudioItemEntity(
                    uriString = "tts:respiracao",
                    label = "Pausa para Respiração",
                    weight = 2,
                    volumePercent = 90,
                    isActive = true,
                    audioType = "TEXT_TO_SPEECH",
                    ttsText = "Inspire profundamente e relaxe os ombros por alguns segundos."
                )
                audioDao.insertAudios(listOf(demoAudio1, demoAudio2, demoAudio3))
            }
        }
    }

