package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.worker.ReminderWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AudioReminderApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Sincroniza o agendamento em background se o aplicativo estiver habilitado
        applicationScope.launch {
            try {
                val prefs = preferencesRepository.preferencesFlow.first()
                if (prefs.isAppMasterEnabled) {
                    ReminderWorkScheduler.rescheduleNextReminder(applicationContext)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Canal para lembretes em áudio com prioridade alta
            val remindersChannel = NotificationChannel(
                CHANNEL_ID_REMINDERS,
                "Lembretes Periódicos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Disparos periódicos de áudios configurados e status de execução"
                enableVibration(true)
                setShowBadge(true)
            }

            // Canal para o serviço em primeiro plano (persistente durante playback)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Serviço de Execução Contínua",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o serviço ativo para precisão de alarmes e horários de descanso"
                setShowBadge(false)
            }

            // Canal de alertas de descanso e status
            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Períodos de Descanso e Avisos",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisos sobre horários de descanso, conflitos de dias e bateria"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(remindersChannel)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_ID_REMINDERS = "audio_reminders_channel"
        const val CHANNEL_ID_SERVICE = "audio_service_channel"
        const val CHANNEL_ID_ALERTS = "audio_alerts_channel"
    }
}
