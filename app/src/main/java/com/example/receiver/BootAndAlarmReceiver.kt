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
                val pendingResult = goAsync()
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.ALARM,
                    message = "BootAndAlarmReceiver recebeu ACTION_TRIGGER_ALARM",
                    details = "Iniciando processamento prioritário de alarme"
                )

                CoroutineScope(Dispatchers.IO).launch {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val wakeLock = powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AudioReminder:AlarmWakeLock"
                    )?.apply {
                        acquire(60_000L)
                    }

                    try {
                        var serviceStarted = false
                        try {
                            val serviceIntent = Intent(context, AudioReminderService::class.java).apply {
                                this.action = AudioReminderService.ACTION_TRIGGER_ALARM
                                putExtras(intent)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            serviceStarted = true
                        } catch (e: Throwable) {
                            AudioDiagnosticLogger.log(
                                level = DiagnosticLevel.WARN,
                                category = DiagnosticCategory.SERVICE,
                                message = "Início do serviço em 1º plano impedido pelo sistema: ${e.message}",
                                details = "Ativando reprodução de áudio direta de contingência"
                            )
                        }

                        // Se o serviço não pôde ser iniciado diretamente em segundo plano (Android 14+),
                        // executa a reprodução imediatamente no próprio receptor enquanto mantém o WakeLock
                        if (!serviceStarted) {
                            executeDirectPlayback(context, intent)
                        }
                    } finally {
                        try {
                            wakeLock?.release()
                        } catch (_: Throwable) {}
                        pendingResult.finish()
                    }
                }
            }

            ReminderNotificationHelper.ACTION_STOP_AUDIO_ALERT -> {
                ReminderNotificationHelper.cancelVisualAlert(context)
                try {
                    val stopPlayerIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_START_OR_REFRESH
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(stopPlayerIntent)
                    } else {
                        context.startService(stopPlayerIntent)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            ReminderNotificationHelper.ACTION_SNOOZE_10_MIN_ALERT -> {
                ReminderNotificationHelper.cancelVisualAlert(context)
                try {
                    val snoozeIntent = Intent(context, AudioReminderService::class.java).apply {
                        this.action = AudioReminderService.ACTION_SNOOZE
                        putExtra(AudioReminderService.EXTRA_SNOOZE_MINUTES, 10)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(snoozeIntent)
                    } else {
                        context.startService(snoozeIntent)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
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
            val profiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
            val activeAudios = database.audioDao().getActiveAudios().map { it.toDomain() }
            val activeReminders = database.reminderDao().getActiveReminders()

            val activeProfile = ScheduleCalculator.findActiveProfileFor(now, profiles)
            val selectedAudio = if (activeProfile != null && activeAudios.isNotEmpty()) {
                activeAudios.firstOrNull { activeProfile.audioIds.contains(it.id) } ?: activeAudios.firstOrNull()
            } else {
                activeAudios.firstOrNull()
            } ?: activeReminders.firstOrNull()?.let { rem ->
                com.example.domain.model.AudioItem(
                    id = rem.id,
                    uriString = rem.audioUri ?: "tts:${rem.title.replace(" ", "_")}",
                    label = rem.title,
                    isActive = true
                )
            }

            if (selectedAudio != null) {
                if (prefs.vibrateWithAudio) {
                    DeviceStateHelper.triggerVibration(context)
                }

                ReminderNotificationHelper.showTriggerVisualAlert(
                    context = context,
                    title = "Lembrete: ${selectedAudio.label}",
                    message = if (selectedAudio.ttsText.isNullOrBlank()) "Lembrete automático reproduzido." else selectedAudio.ttsText,
                    reminderId = selectedAudio.id
                )

                val playerManager = AudioPlayerManager(context)
                val playbackDone = CompletableDeferred<Unit>()

                withContext(Dispatchers.Main) {
                    playerManager.playAudio(
                        uriString = selectedAudio.uriString,
                        targetVolume = (prefs.masterVolumePercent * selectedAudio.volumePercent) / 10000f,
                        fadeInDurationMs = if (prefs.fadeInEnabled) (prefs.fadeInDurationSeconds * 1000).toLong() else 0L,
                        useAppVolumeOnly = prefs.useAppVolumeOnly,
                        audioTitle = selectedAudio.label,
                        ttsText = selectedAudio.ttsText,
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
            }

            // Agenda o próximo alarme com segurança
            AudioReminderService.startOrRefresh(context)
        } catch (e: Throwable) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "Erro na execução direta de áudio: ${e.message}"
            )
        }
    }
}
