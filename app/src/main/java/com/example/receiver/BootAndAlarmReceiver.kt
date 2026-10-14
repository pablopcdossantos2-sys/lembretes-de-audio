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
                try {
                    val serviceIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_TRIGGER_ALARM
                        putExtras(intent)
                    }
                    AudioReminderService.startOrRefresh(context)
                    context.startService(serviceIntent)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            com.example.util.ReminderNotificationHelper.ACTION_STOP_AUDIO_ALERT -> {
                // Para a reprodução imediatamente e cancela o alerta visual
                com.example.util.ReminderNotificationHelper.cancelVisualAlert(context)
                try {
                    val stopPlayerIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_START_OR_REFRESH
                    }
                    context.startService(stopPlayerIntent)
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
                    context.startService(snoozeIntent)
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
