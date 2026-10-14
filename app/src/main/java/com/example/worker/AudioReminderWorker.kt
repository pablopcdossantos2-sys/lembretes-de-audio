package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ReminderEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.model.AudioItem
import com.example.domain.model.Profile
import com.example.domain.scheduler.ScheduleCalculator
import com.example.player.AudioPlayerManager
import com.example.service.AudioReminderService
import com.example.util.DeviceStateHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Worker do WorkManager responsável por executar os lembretes de áudio em segundo plano.
 * Respeita estritamente:
 * 1. O botão mestre de ativação do app e modo soneca (snooze).
 * 2. Os "Horários de Descanso" salvos no Room (tanto períodos configurados em perfis quanto horários de silêncio de cada lembrete).
 * 3. O estado do dispositivo (não perturbe, chamadas em andamento e fones conectados).
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

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val database = AppDatabase.getDatabase(context)
        val preferencesRepo = UserPreferencesRepository(context)

        val prefs = preferencesRepo.getPreferences()
        if (!prefs.isAppMasterEnabled) {
            return@withContext Result.success()
        }

        val nowMillis = System.currentTimeMillis()
        if (prefs.snoozeUntilEpochMillis > nowMillis) {
            return@withContext Result.success()
        }

        val now = ZonedDateTime.now()
        val currentLocalTime = now.toLocalTime()

        // 0. VERIFICAÇÃO DE HORÁRIO DE NÃO PERTURBE / DESCANSO DEFINIDO NAS CONFIGURAÇÕES
        if (prefs.quietHoursEnabled) {
            val isQuietTime = ScheduleCalculator.isTimeInQuietHours(
                time = currentLocalTime,
                dayOfWeek = now.dayOfWeek,
                enabled = prefs.quietHoursEnabled,
                startHour = prefs.quietHoursStartHour,
                startMinute = prefs.quietHoursStartMinute,
                endHour = prefs.quietHoursEndHour,
                endMinute = prefs.quietHoursEndMinute,
                daysOfWeekCsv = prefs.quietHoursDaysCsv
            )

            if (isQuietTime) {
                if (prefs.quietHoursNotifySilently) {
                    showRestNotification(
                        title = "Horário de Não Perturbe / Descanso",
                        message = String.format(
                            Locale.getDefault(),
                            "Lembretes em áudio silenciados das %02d:%02d às %02d:%02d para garantir seu repouso.",
                            prefs.quietHoursStartHour,
                            prefs.quietHoursStartMinute,
                            prefs.quietHoursEndHour,
                            prefs.quietHoursEndMinute
                        )
                    )
                }
                return@withContext Result.success()
            }
        }

        // 1. CARREGAR CONFIGURAÇÕES DE DESCANSO DO ROOM (Perfis e RestingPeriods)
        val profilesWithRests = database.profileDao().getAllProfilesWithRests()
        val domainProfiles = profilesWithRests.map { it.toDomain() }

        // Identifica o perfil ativo para o momento atual (se houver)
        val activeProfile = ScheduleCalculator.findActiveProfileFor(now, domainProfiles)
        if (activeProfile != null) {
            val mergedRests = ScheduleCalculator.mergeRestingPeriods(activeProfile.restingPeriods)
            val isRestingInProfile = ScheduleCalculator.isTimeInRestingPeriod(currentLocalTime, mergedRests)

            if (isRestingInProfile) {
                val coveringRest = ScheduleCalculator.findCoveringRest(currentLocalTime, mergedRests)
                val restLabel = coveringRest?.label?.ifBlank { null } ?: "Horário de Descanso"
                val restEndStr = coveringRest?.endTime?.format(timeFormatter) ?: "breve"

                showRestNotification(
                    title = "Descanso Ativo ($restLabel)",
                    message = "Lembretes silenciados até $restEndStr para garantir seu repouso."
                )
                return@withContext Result.success()
            }
        }

        // 2. CARREGAR LEMBRETES SALVOS NO ROOM (LembreteDao)
        val activeReminders = database.reminderDao().getActiveReminders()
        val activeAudios = database.audioDao().getActiveAudios()

        // Verifica se há um lembrete específico passado como parâmetro
        val specificReminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
        val targetReminder: ReminderEntity? = if (specificReminderId > 0) {
            database.reminderDao().getReminderById(specificReminderId)
        } else {
            // Busca o lembrete cuja janela de horário esteja ativa
            activeReminders.firstOrNull { reminder ->
                isTimeInReminderActiveWindow(currentLocalTime, reminder)
            } ?: activeReminders.firstOrNull()
        }

        // Verifica se o lembrete específico está em seu próprio horário de descanso
        if (targetReminder != null) {
            val isInActiveWindow = isTimeInReminderActiveWindow(currentLocalTime, targetReminder)
            if (!isInActiveWindow) {
                // Fora da janela ativa = em Horário de Descanso do lembrete
                showRestNotification(
                    title = "Lembrete em Horário de Descanso",
                    message = String.format(
                        Locale.getDefault(),
                        "\"%s\" em repouso (ativo apenas das %02d:%02d às %02d:%02d).",
                        targetReminder.title,
                        targetReminder.startHour,
                        targetReminder.startMinute,
                        targetReminder.endHour,
                        targetReminder.endMinute
                    )
                )
                return@withContext Result.success()
            }
        }

        // 3. VERIFICAÇÃO DE ESTADO DO DISPOSITIVO
        if (DeviceStateHelper.isPhoneCallActive(context)) {
            showInfoNotification("Lembrete ignorado", "Chamada telefônica em andamento.")
            return@withContext Result.success()
        }

        if (prefs.respectDoNotDisturb && DeviceStateHelper.isDoNotDisturbActive(context)) {
            showInfoNotification("Lembrete silenciado", "Modo Não Perturbe ativo no sistema.")
            return@withContext Result.success()
        }

        // 4. SELECIONAR E TOCAR O ÁUDIO DO LEMBRETE
        val audioToPlayUri: String? = targetReminder?.audioUri?.ifBlank { null }
            ?: activeAudios.firstOrNull()?.uriString

        val reminderTitle = targetReminder?.title
            ?: activeAudios.firstOrNull()?.label
            ?: "Lembrete Periódico"

        if (audioToPlayUri != null) {
            // Exibe alerta visual na tela de bloqueio com alta prioridade e botões de ação
            com.example.util.ReminderNotificationHelper.showTriggerVisualAlert(
                context = context,
                title = "Lembrete: $reminderTitle",
                message = targetReminder?.text ?: "Lembrete em áudio disparado.",
                reminderId = targetReminder?.id
            )

            // Executa a reprodução com foco e volume calculado
            val volumeFloat = (prefs.masterVolumePercent / 100f).coerceIn(0.1f, 1.0f)
            val playerManager = AudioPlayerManager(context)
            val playbackDone = CompletableDeferred<Unit>()

            val fadeInDurationMs = if (prefs.fadeInEnabled && prefs.fadeInDurationSeconds > 0) {
                (prefs.fadeInDurationSeconds * 1000L).toLong()
            } else {
                0L
            }

            withContext(Dispatchers.Main) {
                playerManager.playAudio(
                    uriString = audioToPlayUri,
                    targetVolume = volumeFloat,
                    fadeInDurationMs = fadeInDurationMs,
                    onComplete = {
                        playbackDone.complete(Unit)
                    }
                )
            }

            // Aguarda o término da reprodução (com timeout seguro de 60 segundos)
            try {
                kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    playbackDone.await()
                }
            } catch (_: Exception) {}
        } else {
            // Caso não haja áudio gravado ou importado, emite notificação sonora padrão
            showPlaybackNotification(
                title = "Lembrete: $reminderTitle",
                message = "Adicione ou grave áudios na biblioteca para reprodução personalizada."
            )
        }

        Result.success()
    }

    /**
     * Verifica se o horário atual está dentro da janela de funcionamento do lembrete.
     * Caso não esteja, significa que está no Horário de Descanso do lembrete.
     */
    private fun isTimeInReminderActiveWindow(time: LocalTime, reminder: ReminderEntity): Boolean {
        val start = LocalTime.of(reminder.startHour, reminder.startMinute)
        val end = LocalTime.of(reminder.endHour, reminder.endMinute)

        return if (start.isAfter(end) || start == end) {
            // Janela que cruza meia-noite (ex: 20:00 às 02:00)
            !time.isBefore(start) || time.isBefore(end)
        } else {
            // Janela dentro do mesmo dia (ex: 08:00 às 22:00)
            !time.isBefore(start) && time.isBefore(end)
        }
    }

    private fun showRestNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notificationManager)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showInfoNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notificationManager)

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showPlaybackNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notificationManager)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Lembretes em Segundo Plano (WorkManager)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Disparos periódicos de lembretes e avisos de horário de descanso"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
