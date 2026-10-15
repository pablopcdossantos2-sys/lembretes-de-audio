package com.example

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
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
        com.example.util.AudioDiagnosticLogger.init(this)
        createNotificationChannels()

        // Sincroniza o serviço e o agendamento em background se o aplicativo estiver habilitado
        applicationScope.launch {
            try {
                val prefs = preferencesRepository.preferencesFlow.first()
                if (prefs.isAppMasterEnabled) {
                    com.example.service.AudioReminderService.startOrRefresh(applicationContext)
                    ReminderWorkScheduler.rescheduleNextReminder(applicationContext)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Canal para lembretes em áudio com prioridade ALTA (intrusivo com som, vibração e banner)
            val remindersChannel = NotificationChannel(
                CHANNEL_ID_REMINDERS,
                "Lembretes Periódicos (Alta Prioridade)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Disparos periódicos de áudios configurados com alertas visuais intrusivos"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 200, 450)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.CYAN
                setShowBadge(true)
                setBypassDnd(false)
            }

            // Canal para o serviço em primeiro plano (persistente em segundo plano)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Serviço de Execução Contínua",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o serviço ativo para precisão de alarmes e horários de descanso"
                setShowBadge(false)
            }

            // Canal de alertas e avisos imediatos (IMPORTANCE_HIGH para visibilidade proeminente)
            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Alertas e Avisos de Lembretes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos sobre horários de descanso, conflitos de dias e status de execução"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.CYAN
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(remindersChannel)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertsChannel)

            // Registra também o canal de alertas de disparo em tela cheia / heads-up
            com.example.util.ReminderNotificationHelper.ensureNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_ID_REMINDERS = "audio_reminders_channel"
        const val CHANNEL_ID_SERVICE = "audio_service_channel"
        const val CHANNEL_ID_ALERTS = "audio_alerts_channel"
    }
}
