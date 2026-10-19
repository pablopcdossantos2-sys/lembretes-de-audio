package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.scheduler.ScheduleCalculator
import com.example.player.AudioPlayerManager
import com.example.service.AudioReminderService
import com.example.util.AudioDiagnosticLogger
import com.example.util.DeviceStateHelper
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
import com.example.util.ReminderNotificationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class BootAndAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            AudioReminderService.ACTION_TRIGGER_ALARM -> {
                // O Receiver não seleciona nem reproduz áudio: delega o disparo ao
                // único caminho de reprodução, no serviço em primeiro plano.
                val serviceIntent = Intent(context, AudioReminderService::class.java).apply {
                    this.action = AudioReminderService.ACTION_TRIGGER_ALARM
                    putExtra(
                        AudioReminderService.EXTRA_EXPECTED_TIME_MILLIS,
                        intent.getLongExtra(AudioReminderService.EXTRA_EXPECTED_TIME_MILLIS, 0L)
                    )
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.ERROR,
                        category = DiagnosticCategory.ALARM,
                        message = "Não foi possível iniciar o serviço para o alarme: ${e.message}"
                    )
                }
            }

            ReminderNotificationHelper.ACTION_STOP_AUDIO_ALERT -> {
                ReminderNotificationHelper.cancelVisualAlert(context)
                val app = context.applicationContext as? com.example.AudioReminderApp
                app?.audioPlayerManager?.stop()
                AudioDiagnosticLogger.logServiceEvent("ACTION_STOP_AUDIO_ALERT", "Áudio interrompido com sucesso pelo usuário")
            }

            ReminderNotificationHelper.ACTION_SNOOZE_10_MIN_ALERT -> {
                ReminderNotificationHelper.cancelVisualAlert(context)
                val app = context.applicationContext as? com.example.AudioReminderApp
                app?.audioPlayerManager?.stop()
                val prefsRepo = UserPreferencesRepository(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val snoozeUntil = System.currentTimeMillis() + (10 * 60 * 1000L)
                    prefsRepo.setSnoozeUntilEpochMillis(snoozeUntil)
                    try { AudioReminderService.startOrRefresh(context) } catch (_: Throwable) {}
                    rescheduleDirectAlarm(context)
                    AudioDiagnosticLogger.logServiceEvent("ACTION_SNOOZE_10_MIN_ALERT", "Modo soneca de 10 minutos ativado")
                }
            }

            ReminderNotificationHelper.ACTION_DISMISS_ALERT -> {
                ReminderNotificationHelper.cancelVisualAlert(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val prefsRepo = UserPreferencesRepository(context)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val database = AppDatabase.getDatabase(context)
                        database.ensureInitialDataSeeded(context)
                        val prefs = prefsRepo.getPreferences()
                        if (prefs.isAppMasterEnabled) {
                            rescheduleDirectAlarm(context)
                            // No boot, apenas reagenda. O serviço de mediaPlayback será
                            // iniciado quando o alarme disparar ou o usuário abrir o app.
                            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(context)
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private suspend fun executeDirectPlayback(context: Context, intent: Intent) {
        try {
            val database = AppDatabase.getDatabase(context)
            val prefsRepo = UserPreferencesRepository(context)
            val prefs = prefsRepo.getPreferences()

            if (!prefs.isAppMasterEnabled) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.SERVICE,
                    message = "Disparo direto cancelado: Aplicativo desligado no botão mestre"
                )
                return
            }

            val nowMillis = System.currentTimeMillis()
            if (prefs.snoozeUntilEpochMillis > nowMillis) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.SERVICE,
                    message = "Disparo direto ignorado: Modo soneca ativo"
                )
                return
            }

            val now = ZonedDateTime.now()

            // 0. Verifica Horário Silencioso Global (Fix A4)
            if (prefs.quietHoursEnabled) {
                val inQuietHours = ScheduleCalculator.isTimeInQuietHours(
                    time = now.toLocalTime(),
                    dayOfWeek = now.dayOfWeek,
                    enabled = prefs.quietHoursEnabled,
                    startHour = prefs.quietHoursStartHour,
                    startMinute = prefs.quietHoursStartMinute,
                    endHour = prefs.quietHoursEndHour,
                    endMinute = prefs.quietHoursEndMinute,
                    daysOfWeekCsv = prefs.quietHoursDaysCsv
                )
                if (inQuietHours) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.INFO,
                        category = DiagnosticCategory.SERVICE,
                        message = "Disparo direto silenciado: Horário Silencioso ativo"
                    )
                    AudioReminderService.startOrRefresh(context)
                    return
                }
            }

            // 1. Verifica Modo Não Perturbe se configurado
            if (prefs.respectDoNotDisturb && DeviceStateHelper.isDoNotDisturbActive(context)) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.ROUTING,
                    message = "Disparo silenciado: Modo Não Perturbe ativo"
                )
                AudioReminderService.startOrRefresh(context)
                return
            }

            // 2. Verifica chamada telefônica em andamento
            if (DeviceStateHelper.isPhoneCallActive(context)) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.ROUTING,
                    message = "Disparo pausado: Chamada telefônica ativa"
                )
                AudioReminderService.startOrRefresh(context)
                return
            }

            val profiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
            val activeAudios = database.audioDao().getActiveAudios().map { it.toDomain() }
            val activeReminders = database.reminderDao().getActiveReminders()

            val activeProfile = ScheduleCalculator.findActiveProfileFor(now, profiles)

            // Se estiver em período de descanso configurado no perfil, não toca
            if (activeProfile != null && activeProfile.restingPeriods.isNotEmpty()) {
                val mergedRests = ScheduleCalculator.mergeRestingPeriods(activeProfile.restingPeriods)
                if (ScheduleCalculator.isTimeInRestingPeriod(now.toLocalTime(), mergedRests)) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.INFO,
                        category = DiagnosticCategory.SERVICE,
                        message = "Disparo direto ignorado: Horário de descanso do perfil ativo"
                    )
                    AudioReminderService.startOrRefresh(context)
                    return
                }
            }

            val eligibleAudios = if (activeProfile != null) {
                if (activeProfile.audioIds.isNotEmpty()) {
                    activeAudios.filter { activeProfile.audioIds.contains(it.id) }
                } else {
                    activeAudios
                }
            } else activeAudios

            val selectedAudio = when {
                activeProfile?.playbackOrder == com.example.domain.model.PlaybackOrder.SPECIFIC && activeProfile.specificAudioId != null -> {
                    activeAudios.firstOrNull { it.id == activeProfile.specificAudioId }
                        ?: eligibleAudios.firstOrNull { it.id == activeProfile.specificAudioId }
                        ?: eligibleAudios.firstOrNull()
                }
                activeProfile?.playbackOrder == com.example.domain.model.PlaybackOrder.RANDOM -> {
                    ScheduleCalculator.pickWeightedRandomAudio(eligibleAudios)
                }
                activeProfile?.playbackOrder == com.example.domain.model.PlaybackOrder.SEQUENTIAL && eligibleAudios.isNotEmpty() -> {
                    val index = (prefs.lastSequentialIndex % eligibleAudios.size + eligibleAudios.size) % eligibleAudios.size
                    prefsRepo.incrementSequentialIndex()
                    eligibleAudios.getOrNull(index) ?: eligibleAudios.firstOrNull()
                }
                else -> eligibleAudios.firstOrNull()
            } ?: activeReminders.firstOrNull()?.let { rem ->
                val uri = rem.audioFilePath?.takeIf { java.io.File(it).exists() }?.let { "file://$it" }
                    ?: rem.audioUri
                    ?: "tts:${rem.title.replace(" ", "_")}"
                com.example.domain.model.AudioItem(
                    id = rem.id,
                    uriString = uri,
                    label = rem.title,
                    isActive = true,
                    ttsText = rem.text
                )
            }

            val finalAudioToPlay = selectedAudio ?: activeAudios.firstOrNull() ?: run {
                val sampleFiles = com.example.util.AudioSampleGenerator.ensureDefaultAudioFiles(context)
                val fallbackFile = sampleFiles.firstOrNull()
                com.example.domain.model.AudioItem(
                    id = 0L,
                    uriString = fallbackFile?.let { "file://${it.absolutePath}" } ?: "tts:lembrete_programado",
                    label = "Lembrete Programado",
                    isActive = true,
                    ttsText = "Atenção: seu lembrete programado foi disparado."
                )
            }

            if (prefs.vibrateWithAudio) {
                DeviceStateHelper.triggerVibration(context)
            }

            ReminderNotificationHelper.showTriggerVisualAlert(
                context = context,
                title = "Lembrete: ${finalAudioToPlay.label}",
                message = if (finalAudioToPlay.ttsText.isNullOrBlank()) "Lembrete automático reproduzido." else finalAudioToPlay.ttsText,
                reminderId = finalAudioToPlay.id
            )

            val playerManager = (context.applicationContext as? com.example.AudioReminderApp)?.audioPlayerManager
                ?: AudioPlayerManager(context)
            val playbackDone = CompletableDeferred<Unit>()

            withContext(Dispatchers.Main) {
                playerManager.playAudio(
                    uriString = finalAudioToPlay.uriString,
                    targetVolume = (prefs.masterVolumePercent * finalAudioToPlay.volumePercent) / 10000f,
                    fadeInDurationMs = if (prefs.fadeInEnabled) (prefs.fadeInDurationSeconds * 1000).toLong() else 0L,
                    useAppVolumeOnly = prefs.useAppVolumeOnly,
                    audioTitle = finalAudioToPlay.label,
                    ttsText = finalAudioToPlay.ttsText,
                    onComplete = {
                        playbackDone.complete(Unit)
                    }
                )
            }

            try {
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    playbackDone.await()
                }
            } catch (_: Throwable) {}

            // Agenda o próximo alarme com segurança
            rescheduleDirectAlarm(context)
            try {
                AudioReminderService.startOrRefresh(context)
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "Erro na execução direta de áudio: ${e.message}"
            )
        }
    }

    private fun rescheduleDirectAlarm(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context)
                val prefsRepo = UserPreferencesRepository(context)
                val prefs = prefsRepo.getPreferences()
                if (!prefs.isAppMasterEnabled) return@launch

                val now = ZonedDateTime.now()
                val nowMillis = System.currentTimeMillis()
                val profiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
                val activeAudios = database.audioDao().getActiveAudios().map { it.toDomain() }
                val activeReminders = database.reminderDao().getActiveReminders()

                val snoozeUntilZoned = if (prefs.snoozeUntilEpochMillis > nowMillis) {
                    ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(prefs.snoozeUntilEpochMillis), java.time.ZoneId.systemDefault())
                } else null

                val effectiveProfiles = if (profiles.none { it.isEnabled } && activeReminders.isNotEmpty()) {
                    activeReminders.map { rem ->
                        com.example.domain.model.Profile(
                            id = rem.id,
                            name = rem.title,
                            isEnabled = true,
                            priority = 20,
                            daysOfWeek = java.time.DayOfWeek.values().toSet(),
                            startTime = java.time.LocalTime.of(0, 0),
                            endTime = java.time.LocalTime.of(23, 59),
                            intervalMinutes = rem.intervalMinutes,
                            playbackOrder = com.example.domain.model.PlaybackOrder.SPECIFIC,
                            specificAudioId = rem.id
                        )
                    }
                } else profiles

                val effectiveAudios = if (activeAudios.isEmpty() && activeReminders.isNotEmpty()) {
                    activeReminders.map { rem ->
                        com.example.domain.model.AudioItem(
                            id = rem.id,
                            uriString = rem.audioFilePath?.takeIf { java.io.File(it).exists() }?.let { "file://$it" } ?: rem.audioUri ?: "tts:${rem.title}",
                            label = rem.title,
                            isActive = true
                        )
                    }
                } else activeAudios

                val nextTrigger = ScheduleCalculator.getNextTrigger(
                    now = now,
                    profiles = effectiveProfiles,
                    activeAudios = effectiveAudios,
                    snoozeUntil = snoozeUntilZoned,
                    lastSequentialIndex = prefs.lastSequentialIndex,
                    quietHoursEnabled = prefs.quietHoursEnabled,
                    quietHoursStartHour = prefs.quietHoursStartHour,
                    quietHoursStartMinute = prefs.quietHoursStartMinute,
                    quietHoursEndHour = prefs.quietHoursEndHour,
                    quietHoursEndMinute = prefs.quietHoursEndMinute,
                    quietHoursDaysCsv = prefs.quietHoursDaysCsv
                )

                if (nextTrigger != null) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return@launch
                    val triggerMillis = nextTrigger.triggerTime.toInstant().toEpochMilli()

                    val intent = Intent(context, BootAndAlarmReceiver::class.java).apply {
                        action = AudioReminderService.ACTION_TRIGGER_ALARM
                        putExtra(AudioReminderService.EXTRA_EXPECTED_TIME_MILLIS, triggerMillis)
                    }

                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        context,
                        2001,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )

                    val canExact = DeviceStateHelper.canScheduleExactAlarms(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (canExact) {
                            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        } else {
                            val showIntent = android.app.PendingIntent.getActivity(
                                context,
                                2002,
                                Intent(context, com.example.MainActivity::class.java),
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            val alarmClockInfo = android.app.AlarmManager.AlarmClockInfo(triggerMillis, showIntent)
                            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                    } else {
                        alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
