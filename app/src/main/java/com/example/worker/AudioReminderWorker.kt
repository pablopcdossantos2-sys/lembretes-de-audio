package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.AudioReminderService
import com.example.util.AudioDiagnosticLogger
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel

/**
 * Worker de recuperação: não reproduz áudio nem decide qual perfil está ativo.
 * A reprodução e as regras de agenda pertencem ao alarme exato e ao serviço.
 */
class AudioReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "AudioReminderWorker"
        const val NOTIFICATION_CHANNEL_ID = "workmanager_reminders_channel"
        const val NOTIFICATION_ID = 2001
        const val KEY_REMINDER_ID = "extra_reminder_id"
        const val KEY_TRIGGER_SOURCE = "extra_trigger_source"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val prefs = UserPreferencesRepository(context).getPreferences()
            if (prefs.isAppMasterEnabled) {
                // O serviço mantém a única cadeia responsável pela agenda; o Worker
                // não toca áudio para evitar disparos duplicados e fora do intervalo.
                AudioReminderService.startOrRefresh(context)
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.WORKER,
                    message = "Worker de segurança executado; reprodução delegada ao serviço"
                )
            }
            Result.success()
        } catch (e: Exception) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.ERROR,
                category = DiagnosticCategory.WORKER,
                message = "Falha na verificação de segurança: ${e.message}"
            )
            Result.retry()
        }
    }
}
