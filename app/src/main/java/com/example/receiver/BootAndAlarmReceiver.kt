package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.scheduler.ScheduleCalculator
import com.example.service.AudioReminderService
import com.example.util.AudioDiagnosticLogger
import com.example.util.DeviceStateHelper
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
import com.example.util.ReminderNotificationHelper
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
                    // Fallback visual de alta prioridade se o sistema operacional restringir o serviço
                    ReminderNotificationHelper.showTriggerVisualAlert(
                        context = context,
                        title = "Lembrete Programado",
                        message = "Horário do lembrete atingido. Toque para abrir."
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
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val snoozeUntil = System.currentTimeMillis() + (10 * 60 * 1000L)
                        prefsRepo.setSnoozeUntilEpochMillis(snoozeUntil)
                        // Recalcula o alarme sem iniciar um foreground service a partir do receiver.
                        BootAndAlarmReceiver.rescheduleDirectAlarm(context)
                        AudioDiagnosticLogger.logServiceEvent("ACTION_SNOOZE_10_MIN_ALERT", "Modo soneca de 10 minutos ativado")
                    } catch (e: Throwable) {
                        AudioDiagnosticLogger.log(
                            level = DiagnosticLevel.ERROR,
                            category = DiagnosticCategory.ALARM,
                            message = "Falha ao ativar soneca: ${e.message}"
                        )
                    } finally {
                        pendingResult.finish()
                    }
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
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val database = AppDatabase.getDatabase(context)
                        database.ensureInitialDataSeeded(context)
                        val prefs = prefsRepo.getPreferences()
                        if (prefs.isAppMasterEnabled) {
                            BootAndAlarmReceiver.rescheduleDirectAlarm(context)
                            // No boot, apenas reagenda. O serviço de mediaPlayback será
                            // iniciado quando o alarme disparar ou o usuário abrir o app.
                            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(context)
                        }
                    } catch (e: Throwable) {
                        AudioDiagnosticLogger.log(
                            level = DiagnosticLevel.ERROR,
                            category = DiagnosticCategory.ALARM,
                            message = "Falha ao reagendar lembretes após evento do sistema: ${e.message}"
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        suspend fun rescheduleDirectAlarm(context: Context) {
            withContext(Dispatchers.IO) {
                try {
                    val database = AppDatabase.getDatabase(context)
                    val prefsRepo = UserPreferencesRepository(context)
                    val prefs = prefsRepo.getPreferences()
                    if (!prefs.isAppMasterEnabled) return@withContext

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
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return@withContext
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.ERROR,
                        category = DiagnosticCategory.ALARM,
                        message = "Falha ao recalcular o próximo alarme: ${e.message}"
                    )
                }
            }
        }
    }
}