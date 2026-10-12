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
                val serviceIntent = Intent(context, AudioReminderService::class.java).apply {
                    this.action = AudioReminderService.ACTION_TRIGGER_ALARM
                    putExtras(intent)
                }
                AudioReminderService.startOrRefresh(context)
                context.startService(serviceIntent)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // If app is enabled in preferences, restart foreground service and reschedule WorkManager
                val prefsRepo = UserPreferencesRepository(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val prefs = prefsRepo.getPreferences()
                    if (prefs.isAppMasterEnabled) {
                        AudioReminderService.startOrRefresh(context)
                        com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(context)
                    }
                }
            }
        }
    }
}
