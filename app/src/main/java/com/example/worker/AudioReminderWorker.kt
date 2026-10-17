package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Color
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
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
import com.example.util.AudioDiagnosticLogger
import com.example.util.DeviceStateHelper
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
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
        val timeSecondsStr = currentLocalTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val currentDayOfWeek = now.dayOfWeek

        Log.d(TAG, "==================================================")
        Log.d(TAG, "[AudioReminderWorker] Verificando disparos às $timeSecondsStr (Dia: $currentDayOfWeek)")
        Log.d(TAG, "==================================================")

        // 0. VERIFICAÇÃO DE HORÁRIO DE NÃO PERTURBE / DESCANSO DEFINIDO NAS CONFIGURAÇÕES
        if (prefs.quietHoursEnabled) {
            val isQuietTime = ScheduleCalculator.isTimeInQuietHours(
                time = currentLocalTime,
                dayOfWeek = currentDayOfWeek,
                enabled = prefs.quietHoursEnabled,
                startHour = prefs.quietHoursStartHour,
                startMinute = prefs.quietHoursStartMinute,
                endHour = prefs.quietHoursEndHour,
                endMinute = prefs.quietHoursEndMinute,
                daysOfWeekCsv = prefs.quietHoursDaysCsv
            )

            val quietInfo = String.format(
                Locale.getDefault(),
                "[QuietHours] Ativo: %b | Janela: %02d:%02d às %02d:%02d | Dias: %s | Horário atual: %s | Está em repouso: %b",
                prefs.quietHoursEnabled,
                prefs.quietHoursStartHour,
                prefs.quietHoursStartMinute,
                prefs.quietHoursEndHour,
                prefs.quietHoursEndMinute,
                prefs.quietHoursDaysCsv,
                timeSecondsStr,
                isQuietTime
            )
            Log.d(TAG, quietInfo)

            if (isQuietTime) {
                Log.w(TAG, "[BLOQUEADO] Horário atual $timeSecondsStr está dentro do Não Perturbe global das preferências. Disparo cancelado.")
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.WORKER,
                    message = "Disparo bloqueado: Modo Não Perturbe ativo ($timeSecondsStr)",
                    details = "Janela das %02d:%02d às %02d:%02d".format(prefs.quietHoursStartHour, prefs.quietHoursStartMinute, prefs.quietHoursEndHour, prefs.quietHoursEndMinute)
                )

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

        Log.d(TAG, "[Room DB] Carregados ${domainProfiles.size} perfil(is) do Room para avaliação de descanso.")

        // Identifica o perfil ativo para o momento atual (se houver)
        val activeProfile = ScheduleCalculator.findActiveProfileFor(now, domainProfiles)
        if (activeProfile != null) {
            val restingPeriods = activeProfile.restingPeriods
            Log.d(
                TAG,
                String.format(
                    Locale.getDefault(),
                    "[Perfil Ativo] Nome: '%s' (ID: %d, Prioridade: %d, Horário de Operação: %02d:%02d às %02d:%02d). Descansos no Room: %d",
                    activeProfile.name,
                    activeProfile.id,
                    activeProfile.priority,
                    activeProfile.startTime.hour,
                    activeProfile.startTime.minute,
                    activeProfile.endTime.hour,
                    activeProfile.endTime.minute,
                    restingPeriods.size
                )
            )

            if (restingPeriods.isNotEmpty()) {
                // Log detalhado comparando o horário atual com cada intervalo de descanso salvo no Room
                for ((index, rest) in restingPeriods.withIndex()) {
                    val isInside = rest.contains(currentLocalTime)
                    val crossesMidnightStr = if (rest.crossesMidnight) "atravessa meia-noite" else "mesmo dia"
                    val comparisonLog = String.format(
                        Locale.getDefault(),
                        "[COMPARAÇÃO DESCANSO ROOM #%d] Horário atual %s vs Intervalo [%s] %02d:%02d às %02d:%02d (%s) -> DENTRO DO DESCANSO = %b",
                        index + 1,
                        timeSecondsStr,
                        rest.label,
                        rest.startTime.hour,
                        rest.startTime.minute,
                        rest.endTime.hour,
                        rest.endTime.minute,
                        crossesMidnightStr,
                        isInside
                    )
                    Log.d(TAG, comparisonLog)
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.INFO,
                        category = DiagnosticCategory.WORKER,
                        message = comparisonLog,
                        details = "Perfil: '${activeProfile.name}' (ID: ${activeProfile.id})"
                    )
                }

                val mergedRests = ScheduleCalculator.mergeRestingPeriods(restingPeriods)
                val isRestingInProfile = ScheduleCalculator.isTimeInRestingPeriod(currentLocalTime, mergedRests)

                if (isRestingInProfile) {
                    val coveringRest = ScheduleCalculator.findCoveringRest(currentLocalTime, mergedRests)
                    val restLabel = coveringRest?.label?.ifBlank { null } ?: "Horário de Descanso"
                    val restStartStr = coveringRest?.startTime?.format(timeFormatter) ?: "início"
                    val restEndStr = coveringRest?.endTime?.format(timeFormatter) ?: "breve"

                    val blockedLog = String.format(
                        Locale.getDefault(),
                        "[BLOQUEADO] Horário atual %s ESTÁ DENTRO do descanso '%s' (%s às %s) do perfil ativo '%s'. Notificação e áudio CANCELADOS.",
                        timeSecondsStr,
                        restLabel,
                        restStartStr,
                        restEndStr,
                        activeProfile.name
                    )
                    Log.w(TAG, blockedLog)
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.WARN,
                        category = DiagnosticCategory.WORKER,
                        message = blockedLog,
                        details = "Nenhuma notificação de disparo será emitida para preservar o descanso."
                    )

                    // Apenas exibe notificação se o usuário ativou o aviso nas configurações de descanso
                    if (prefs.quietHoursEnabled && prefs.quietHoursNotifySilently) {
                        showRestNotification(
                            title = "Descanso Ativo ($restLabel)",
                            message = "Lembretes silenciados até $restEndStr para garantir seu repouso."
                        )
                    }
                    return@withContext Result.success()
                } else {
                    val allowedLog = String.format(
                        Locale.getDefault(),
                        "[LIBERADO] Horário atual %s está FORA de todos os %d descansos do perfil ativo '%s'. Notificação autorizada.",
                        timeSecondsStr,
                        restingPeriods.size,
                        activeProfile.name
                    )
                    Log.i(TAG, allowedLog)
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.INFO,
                        category = DiagnosticCategory.WORKER,
                        message = allowedLog,
                        details = "Disparo pode prosseguir normalmente."
                    )
                }
            } else {
                Log.d(TAG, "[Descanso Room] O perfil ativo '${activeProfile.name}' não possui períodos de descanso configurados no Room.")
            }
        } else {
            Log.d(TAG, "[Perfil Ativo] Nenhum perfil ativo no momento para $timeSecondsStr.")
        }

        // 2. CARREGAR LEMBRETES SALVOS NO ROOM (ReminderDao)
        val activeReminders = database.reminderDao().getActiveReminders()
        val activeAudios = database.audioDao().getActiveAudios()
        val isManualTest = inputData.getString(KEY_TRIGGER_SOURCE) == "MANUAL_TEST"

        // Verifica se há um lembrete específico passado como parâmetro
        val specificReminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
        val targetReminder: ReminderEntity? = if (specificReminderId > 0) {
            database.reminderDao().getReminderById(specificReminderId)
        } else {
            // Busca apenas lembrete cuja janela de horário esteja ativa
            activeReminders.firstOrNull { reminder ->
                isTimeInReminderActiveWindow(currentLocalTime, reminder)
            } ?: if (isManualTest) activeReminders.firstOrNull() else null
        }

        // Se não houver lembrete ativo nem áudios globais cadastrados, finaliza suavemente
        if (targetReminder == null && activeAudios.isEmpty()) {
            Log.d(TAG, "[AudioReminderWorker] Nenhum lembrete ou áudio ativo disponível para disparo às $timeSecondsStr.")
            return@withContext Result.success()
        }

        // Verifica se o lembrete específico possui perfil vinculado com descanso
        if (targetReminder != null && targetReminder.profileId != null) {
            val linkedProfile = domainProfiles.firstOrNull { it.id == targetReminder.profileId }
            if (linkedProfile != null && linkedProfile.id != activeProfile?.id && linkedProfile.restingPeriods.isNotEmpty()) {
                val isRestingInLinked = ScheduleCalculator.isTimeInRestingPeriod(currentLocalTime, linkedProfile.restingPeriods)
                Log.d(TAG, "[Perfil Vinculado] Lembrete '${targetReminder.title}' vinculado ao perfil '${linkedProfile.name}'. Em descanso: $isRestingInLinked")
                if (isRestingInLinked && !isManualTest) {
                    Log.w(TAG, "[BLOQUEADO] Horário atual $timeSecondsStr está dentro do descanso do perfil vinculado '${linkedProfile.name}'.")
                    return@withContext Result.success()
                }
            }
        }

        // Verifica se o lembrete específico está em seu próprio horário de descanso
        if (targetReminder != null && !isManualTest) {
            val isInActiveWindow = isTimeInReminderActiveWindow(currentLocalTime, targetReminder)
            Log.d(TAG, "[Lembrete Janela] '${targetReminder.title}' (hasRestPeriod: ${targetReminder.hasRestPeriod}). Ativo agora: $isInActiveWindow")
            if (!isInActiveWindow) {
                // Fora da janela ativa e com descanso programado:
                val reminderRestLog = String.format(
                    Locale.getDefault(),
                    "[BLOQUEADO] Lembrete '%s' está em horário de repouso (janela ativa: %02d:%02d às %02d:%02d).",
                    targetReminder.title,
                    targetReminder.startHour,
                    targetReminder.startMinute,
                    targetReminder.endHour,
                    targetReminder.endMinute
                )
                Log.i(TAG, reminderRestLog)
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.WORKER,
                    message = reminderRestLog
                )
                if (prefs.quietHoursEnabled && prefs.quietHoursNotifySilently) {
                    showRestNotification(
                        title = "Lembrete em Horário de Descanso",
                        message = String.format(
                            Locale.getDefault(),
                            "\"%s\" em repouso (ativo das %02d:%02d às %02d:%02d).",
                            targetReminder.title,
                            targetReminder.startHour,
                            targetReminder.startMinute,
                            targetReminder.endHour,
                            targetReminder.endMinute
                        )
                    )
                }
                return@withContext Result.success()
            }
        }

        // 3. VERIFICAÇÃO DE ESTADO DO DISPOSITIVO
        if (DeviceStateHelper.isPhoneCallActive(context)) {
            Log.w(TAG, "[DISPOSITIVO] Chamada telefônica em andamento. Notificação e áudio adiados.")
            showInfoNotification("Lembrete ignorado", "Chamada telefônica em andamento.")
            return@withContext Result.success()
        }

        if (prefs.respectDoNotDisturb && DeviceStateHelper.isDoNotDisturbActive(context)) {
            Log.w(TAG, "[DISPOSITIVO] Modo Não Perturbe do sistema ativo. Lembrete silenciado.")
            showInfoNotification("Lembrete silenciado", "Modo Não Perturbe ativo no sistema.")
            return@withContext Result.success()
        }

        // 4. SELECIONAR E TOCAR O ÁUDIO DO LEMBRETE
        val audioToPlayUri: String? = targetReminder?.audioFilePath?.takeIf { java.io.File(it).exists() }?.let { "file://$it" }
            ?: targetReminder?.audioUri?.ifBlank { null }
            ?: activeAudios.firstOrNull()?.uriString

        val reminderTitle = targetReminder?.title
            ?: activeAudios.firstOrNull()?.label
            ?: "Lembrete Periódico"

        // GARANTIA FINAL: Notificação SÓ é disparada se o horário atual NÃO estiver dentro
        // de nenhum intervalo de descanso do perfil ativo
        if (activeProfile != null && activeProfile.restingPeriods.isNotEmpty()) {
            val isRestingDoubleCheck = ScheduleCalculator.isTimeInRestingPeriod(currentLocalTime, activeProfile.restingPeriods)
            if (isRestingDoubleCheck) {
                Log.w(TAG, "[SEGURANÇA] Disparo impedido: horário $timeSecondsStr coincide com descanso do perfil ativo '${activeProfile.name}'.")
                return@withContext Result.success()
            }
        }

        if (audioToPlayUri != null) {
            val authMsg = "[DISPARO AUTORIZADO] Emitindo notificação visual e áudio para '$reminderTitle' às $timeSecondsStr"
            Log.i(TAG, authMsg)
            com.example.util.AudioDiagnosticLogger.log(
                level = com.example.util.DiagnosticLevel.SUCCESS,
                category = com.example.util.DiagnosticCategory.WORKER,
                message = authMsg,
                details = "URI: $audioToPlayUri | Perfil: ${activeProfile?.name ?: "Padrão"}"
            )

            // Exibe alerta visual na tela de bloqueio com alta prioridade e botões de ação
            com.example.util.ReminderNotificationHelper.showTriggerVisualAlert(
                context = context,
                title = "Lembrete: $reminderTitle",
                message = targetReminder?.text?.ifBlank { null } ?: "Lembrete em áudio disparado.",
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
                    audioTitle = reminderTitle,
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
     * Se hasRestPeriod for falso, o lembrete está ativo 24 horas por dia (nunca descansa).
     */
    private fun isTimeInReminderActiveWindow(time: LocalTime, reminder: ReminderEntity): Boolean {
        if (!reminder.hasRestPeriod) {
            return true
        }

        val start = LocalTime.of(reminder.startHour.coerceIn(0, 23), reminder.startMinute.coerceIn(0, 59))
        val end = LocalTime.of(reminder.endHour.coerceIn(0, 23), reminder.endMinute.coerceIn(0, 59))

        if (start == end) {
            return true
        }

        return if (start.isAfter(end)) {
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
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("🔔 $title")
                    .bigText(message)
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setVibrate(longArrayOf(0, 450, 200, 450))
            .setLights(Color.CYAN, 1000, 500)
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
                description = "Disparos periódicos de lembretes e avisos visuais de alta prioridade"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 200, 450)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.CYAN
                setShowBadge(true)
                setBypassDnd(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
