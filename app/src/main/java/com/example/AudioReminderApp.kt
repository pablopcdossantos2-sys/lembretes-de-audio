package com.example

import com.example.R

/*
 * Lembretes em Áudio / Audio Reminders
 * Aplicativo Android nativo desenvolvido de forma independente por Pablo Phillipe Cândido dos Santos
 * para agendar e reproduzir lembretes sonoros e gravações em rotinas configuráveis.
 * O desenvolvimento contou com ferramentas de IA generativa como recurso auxiliar.
 * Independent Android app by Pablo Phillipe Cândido dos Santos for scheduled audio reminders.
 * Generative AI tools were used as development aids; project decisions and verification remain the author's responsibility.
 * Currículo Lattes: http://lattes.cnpq.br/9500873674712528
 */

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

    val audioPlayerManager: com.example.player.AudioPlayerManager by lazy {
        com.example.player.AudioPlayerManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        com.example.util.AudioDiagnosticLogger.init(this)
        createNotificationChannels()

        // Pré-inicializa os arquivos de som locais e motor de áudio
        try {
            com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(this)
            audioPlayerManager
        } catch (_: Throwable) {}

        // Sincroniza o serviço e o agendamento em background se o aplicativo estiver habilitado
        applicationScope.launch {
            try {
                // Garante que perfis e áudios padrão existam antes de agendar
                database.ensureInitialDataSeeded(applicationContext)
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
                getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_reminders_name)
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
                getString(R.string.channel_service_name),
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
