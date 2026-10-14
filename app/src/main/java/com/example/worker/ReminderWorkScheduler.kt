package com.example.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Utilitário para gerenciar o agendamento de tarefas periódicas e pontuais
 * de lembretes em áudio via Android WorkManager.
 */
object ReminderWorkScheduler {

    const val UNIQUE_PERIODIC_WORK_NAME = "AudioReminderPeriodicWorker"
    const val UNIQUE_ONE_TIME_WORK_NAME = "AudioReminderNextOneTimeWorker"
    const val WORK_TAG_AUDIO_REMINDER = "audio_reminder_work_tag"

    private fun getWorkManager(context: Context): WorkManager? {
        return try {
            WorkManager.getInstance(context)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Agenda a execução periódica do Worker em segundo plano.
     * O intervalo mínimo suportado pelo Android WorkManager é 15 minutos.
     */
    fun schedulePeriodicWork(
        context: Context,
        intervalMinutes: Long = 15,
        flexMinutes: Long = 5
    ) {
        val workManager = getWorkManager(context) ?: return

        // Restrições básicas para permitir execução suave em segundo plano
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<AudioReminderWorker>(
            repeatInterval = intervalMinutes.coerceAtLeast(15),
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = flexMinutes.coerceAtLeast(5),
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG_AUDIO_REMINDER)
            .build()

        try {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWorkRequest
            )
        } catch (e: Throwable) {
            try {
                workManager.enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    periodicWorkRequest
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    /**
     * Agenda uma execução pontual com atraso específico em milissegundos
     * (útil para disparos precisos baseados na grade de horários dos perfis).
     */
    fun scheduleOneTimeReminderWork(
        context: Context,
        delayMillis: Long,
        reminderId: Long? = null
    ) {
        val workManager = getWorkManager(context) ?: return

        val inputDataBuilder = Data.Builder()
            .putString(AudioReminderWorker.KEY_TRIGGER_SOURCE, "SCHEDULED_TRIGGER")
        if (reminderId != null && reminderId > 0) {
            inputDataBuilder.putLong(AudioReminderWorker.KEY_REMINDER_ID, reminderId)
        }

        val oneTimeRequest = OneTimeWorkRequestBuilder<AudioReminderWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(inputDataBuilder.build())
            .addTag(WORK_TAG_AUDIO_REMINDER)
            .build()

        try {
            workManager.enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /**
     * Dispara uma verificação imediata via WorkManager (útil para testes na interface).
     */
    fun enqueueImmediateTest(context: Context) {
        val workManager = getWorkManager(context) ?: return
        val immediateRequest = OneTimeWorkRequestBuilder<AudioReminderWorker>()
            .addTag(WORK_TAG_AUDIO_REMINDER)
            .setInputData(
                Data.Builder()
                    .putString(AudioReminderWorker.KEY_TRIGGER_SOURCE, "MANUAL_TEST")
                    .build()
            )
            .build()

        try {
            workManager.enqueue(immediateRequest)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /**
     * Cancela todos os agendamentos do WorkManager para os lembretes.
     */
    fun cancelAllWork(context: Context) {
        val workManager = getWorkManager(context) ?: return
        try {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
            workManager.cancelAllWorkByTag(WORK_TAG_AUDIO_REMINDER)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /**
     * Lê as configurações ativas do Room e preferências, agendando
     * a periodicidade ideal no WorkManager.
     */
    fun scheduleWorkBasedOnActiveSettings(context: Context) {
        val appContext = try { context.applicationContext } catch (e: Throwable) { null } ?: context
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(appContext)
                val prefsRepo = UserPreferencesRepository(appContext)
                val prefs = prefsRepo.getPreferences()

                if (!prefs.isAppMasterEnabled) {
                    cancelAllWork(appContext)
                    return@launch
                }

                // Descobre a periodicidade configurada nos perfis ou nos lembretes
                val activeReminders = database.reminderDao().getActiveReminders()
                val activeProfiles = database.profileDao().getAllProfilesWithRests().filter { it.profile.isEnabled }

                val minPeriodicity = when {
                    activeReminders.isNotEmpty() -> {
                        activeReminders.minOf { it.periodicityMinutes.toLong() }.coerceAtLeast(15)
                    }
                    activeProfiles.isNotEmpty() -> {
                        activeProfiles.minOf { it.profile.intervalMinutes.toLong() }.coerceAtLeast(15)
                    }
                    else -> 15L
                }

                // Agenda trabalho periódico via WorkManager
                schedulePeriodicWork(
                    context = appContext,
                    intervalMinutes = minPeriodicity,
                    flexMinutes = 5
                )
            } catch (e: Throwable) {
                // Suprime exceções em ambientes de teste sem Room completo inicializado
            }
        }
    }

    /**
     * Retorna o fluxo de status dos Workers para exibição na UI.
     */
    fun getWorkInfosFlow(context: Context): Flow<List<WorkInfo>> {
        val workManager = getWorkManager(context)
        return workManager?.getWorkInfosByTagFlow(WORK_TAG_AUDIO_REMINDER)
            ?: kotlinx.coroutines.flow.emptyFlow()
    }
}
