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
                    details = "Executando reprodução de áudio prioritária com WakeLock ativo"
                )

                CoroutineScope(Dispatchers.IO).launch {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val wakeLock = powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AudioReminder:AlarmWakeLock"
                    )?.apply {
                        acquire(120_000L)
                    }

                    try {
                        executeDirectPlayback(context, intent)
                    } finally {
                        try {
                            if (wakeLock?.isHeld == true) {
                                wakeLock.release()
                            }
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

                val playerManager = (context.applicationContext as? com.example.AudioReminderApp)?.audioPlayerManager
                    ?: AudioPlayerManager(context)
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
