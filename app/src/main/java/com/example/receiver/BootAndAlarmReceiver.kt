package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.AudioReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootAndAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            AudioReminderService.ACTION_TRIGGER_ALARM -> {
                // Forward alarm trigger to the foreground service
                com.example.util.AudioDiagnosticLogger.log(
                    level = com.example.util.DiagnosticLevel.INFO,
                    category = com.example.util.DiagnosticCategory.ALARM,
                    message = "BootAndAlarmReceiver recebeu ACTION_TRIGGER_ALARM",
                    details = "Encaminhando para AudioReminderService com WakeLock"
                )
                try {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val wakeLock = powerManager?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "AudioReminder:AlarmWakeLock"
                    )?.apply {
                        acquire(60_000L) // 60 segundos para garantir que o serviço inicie e toque o áudio
                    }

                    val serviceIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_TRIGGER_ALARM
                        putExtras(intent)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Throwable) {
                    com.example.util.AudioDiagnosticLogger.log(
                        level = com.example.util.DiagnosticLevel.WARN,
                        category = com.example.util.DiagnosticCategory.SERVICE,
                        message = "Falha ao iniciar AudioReminderService via Receiver: ${e.message}",
                        details = "Acionando WorkManager como contingência imediata"
                    )
                    try {
                        val oneTime = androidx.work.OneTimeWorkRequestBuilder<com.example.worker.AudioReminderWorker>()
                            .setInputData(
                                androidx.work.Data.Builder()
                                    .putString(com.example.worker.AudioReminderWorker.KEY_TRIGGER_SOURCE, "ALARM_RECEIVER_FALLBACK")
                                    .build()
                            )
                            .build()
                        androidx.work.WorkManager.getInstance(context).enqueue(oneTime)
                    } catch (_: Throwable) {}
                }
            }

            com.example.util.ReminderNotificationHelper.ACTION_STOP_AUDIO_ALERT -> {
                // Para a reprodução imediatamente e cancela o alerta visual
                com.example.util.ReminderNotificationHelper.cancelVisualAlert(context)
                try {
                    val stopPlayerIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_START_OR_REFRESH
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(stopPlayerIntent)
                    } else {
                        context.startService(stopPlayerIntent)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            com.example.util.ReminderNotificationHelper.ACTION_SNOOZE_10_MIN_ALERT -> {
                // Adia os lembretes por 10 minutos e cancela o alerta visual
                com.example.util.ReminderNotificationHelper.cancelVisualAlert(context)
                try {
                    val snoozeIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_SNOOZE
                        putExtra(AudioReminderService.EXTRA_SNOOZE_MINUTES, 10)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(snoozeIntent)
                    } else {
                        context.startService(snoozeIntent)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            com.example.util.ReminderNotificationHelper.ACTION_DISMISS_ALERT -> {
                com.example.util.ReminderNotificationHelper.cancelVisualAlert(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // If app is enabled in preferences, restart foreground service and reschedule WorkManager
                val prefsRepo = UserPreferencesRepository(context)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val prefs = prefsRepo.getPreferences()
                        if (prefs.isAppMasterEnabled) {
                            AudioReminderService.startOrRefresh(context)
                            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(context)
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
