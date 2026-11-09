package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.AudioReminderApp
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.db.ReminderScheduleAdapter
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.ScheduledTrigger
import com.example.domain.scheduler.ScheduleCalculator
import com.example.player.AudioPlayerManager
import com.example.receiver.BootAndAlarmReceiver
import com.example.util.AudioDiagnosticLogger
import com.example.util.DeviceStateHelper
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AudioReminderService : Service() {

    companion object {
        const val CHANNEL_SERVICE_ID = "audio_reminder_service"
        const val CHANNEL_ALERTS_ID = "audio_reminder_alerts"
        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_ALERT = 1002

        const val ACTION_START_OR_REFRESH = "com.example.action.START_OR_REFRESH"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"
        const val ACTION_TRIGGER_ALARM = "com.example.action.TRIGGER_ALARM"
        const val ACTION_SNOOZE = "com.example.action.SNOOZE"
        const val ACTION_RESUME = "com.example.action.RESUME"

        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_EXPECTED_TIME_MILLIS = "extra_expected_time_millis"

        fun startOrRefresh(context: Context) {
            try {
                val intent = Intent(context, AudioReminderService::class.java).apply {
                    action = ACTION_START_OR_REFRESH
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AudioReminderService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var database: AppDatabase
    private lateinit var preferencesRepo: UserPreferencesRepository
    private lateinit var playerManager: AudioPlayerManager
    private val alarmTriggerChannel = Channel<Long>(Channel.UNLIMITED)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        preferencesRepo = UserPreferencesRepository(this)
        playerManager = (application as? AudioReminderApp)?.audioPlayerManager ?: AudioPlayerManager(this)
        createNotificationChannels()
        serviceScope.launch {
            for (expectedMillis in alarmTriggerChannel) {
                processAlarmTrigger(expectedMillis)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Garante que startForeground seja chamado imediatamente para cumprir o contrato do Android O+
        try {
            val notification = buildForegroundNotification("Monitorando lembretes em áudio", false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        val actionName = intent?.action ?: "ACTION_DEFAULT"
        AudioDiagnosticLogger.logServiceEvent(
            action = actionName,
            message = "Serviço ativo em primeiro plano",
            details = "Monitorando lembretes com prioridade de mídia"
        )

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                AudioDiagnosticLogger.logServiceEvent(ACTION_STOP_SERVICE, "Parando serviço de lembretes")
                serviceScope.launch {
                    preferencesRepo.setAppMasterEnabled(false)
                    cancelExistingAlarm()
                    playerManager.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 60)
                AudioDiagnosticLogger.logServiceEvent(ACTION_SNOOZE, "Silenciando lembretes por $minutes minutos")
                serviceScope.launch {
                    val snoozeUntil = System.currentTimeMillis() + (minutes * 60 * 1000L)
                    preferencesRepo.setSnoozeUntilEpochMillis(snoozeUntil)
                    playerManager.stop()
                    refreshScheduleAndNotification()
                }
            }
            ACTION_RESUME -> {
                AudioDiagnosticLogger.logServiceEvent(ACTION_RESUME, "Retomando lembretes do modo soneca")
                serviceScope.launch {
                    preferencesRepo.setSnoozeUntilEpochMillis(0L)
                    refreshScheduleAndNotification()
                }
            }
            ACTION_TRIGGER_ALARM -> {
                val expectedMillis = intent.getLongExtra(EXTRA_EXPECTED_TIME_MILLIS, 0L)
                handleAlarmTrigger(expectedMillis)
            }
            else -> {
                serviceScope.launch {
                    refreshScheduleAndNotification()
                }
            }
        }

        return START_STICKY
    }

    private fun handleAlarmTrigger(expectedMillis: Long) {
        val queued = alarmTriggerChannel.trySend(expectedMillis)
        if (queued.isFailure) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.ERROR,
                category = DiagnosticCategory.ALARM,
                message = "Não foi possível enfileirar o disparo recebido",
                details = queued.exceptionOrNull()?.message
            )
        }
    }

    /**
     * Processes alarm events sequentially. Triggers received while another recording
     * is playing stay queued instead of being discarded.
     */
    private suspend fun processAlarmTrigger(expectedMillis: Long) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = try {
            powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "AudioReminder:PlaybackWakeLock"
            )?.apply {
                acquire(180_000L)
            }
        } catch (error: Exception) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.SERVICE,
                message = "Não foi possível adquirir o WakeLock; o alarme continuará sem ele",
                details = error.message
            )
            null
        }

        try {
            val prefs = preferencesRepo.getPreferences()
            if (!prefs.isAppMasterEnabled) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.SERVICE,
                    message = "Disparo ignorado: aplicativo mestre está desligado",
                    details = "Ative a chave principal na tela inicial para habilitar"
                )
                stopSelf()
                return
            }

            // Agenda o próximo instante antes de iniciar qualquer reprodução.
            try {
                refreshScheduleAndNotification()
            } catch (scheduleError: Exception) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.ERROR,
                    category = DiagnosticCategory.ALARM,
                    message = "Falha ao pré-agendar o próximo lembrete",
                    details = scheduleError.message
                )
            }

            val now = ZonedDateTime.now()
            val nowMillis = System.currentTimeMillis()
            val scheduledTime = if (expectedMillis > 0L) {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(expectedMillis), ZoneId.systemDefault())
            } else {
                now.withSecond(0).withNano(0)
            }

            if (expectedMillis > 0L && (nowMillis - expectedMillis) > 900_000L) {
                val missedTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(expectedMillis),
                    ZoneId.systemDefault()
                )
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.ALARM,
                    message = "Lembrete com atraso excessivo de ${(nowMillis - expectedMillis) / 1000}s",
                    details = "Esperado às ${missedTime.format(timeFormatter)}"
                )
                showAlertNotification(
                    title = "Lembrete perdido",
                    message = "Lembrete perdido às ${missedTime.format(timeFormatter)}"
                )
                refreshScheduleAndNotification()
                return
            } else if (expectedMillis > 0L && (nowMillis - expectedMillis) > 10_000L) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.ALARM,
                    message = "Alarme executado com ${(nowMillis - expectedMillis) / 1000}s de tolerância do sistema",
                    details = "Disparo mantido para audibilidade garantida"
                )
            }

            if (prefs.snoozeUntilEpochMillis > nowMillis) {
                val snoozeTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(prefs.snoozeUntilEpochMillis),
                    ZoneId.systemDefault()
                )
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.SERVICE,
                    message = "Lembrete ignorado: modo soneca ativo até ${snoozeTime.format(timeFormatter)}"
                )
                refreshScheduleAndNotification()
                return
            }

            val storedProfiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
            val storedAudios = database.audioDao().getActiveAudios().map { it.toDomain() }
            val activeReminders = database.reminderDao().getActiveReminders()
            val scheduleData = ReminderScheduleAdapter.withLegacyReminders(
                profiles = storedProfiles,
                audios = storedAudios,
                reminders = activeReminders
            )
            val profiles = scheduleData.profiles
            val activeAudios = scheduleData.audios

            if (activeAudios.isEmpty()) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.PLAYBACK,
                    message = "Nenhum áudio ativo na biblioteca nem lembretes cadastrados",
                    details = "Adicione áudios na biblioteca ou grave lembretes para ouvir"
                )
                showAlertNotification(
                    title = "Nenhum áudio ativo",
                    message = "Adicione áudios ou grave lembretes para tocar."
                )
                refreshScheduleAndNotification()
                return
            }

            val inQuietHours = ScheduleCalculator.isTimeInQuietHours(
                time = scheduledTime.toLocalTime(),
                dayOfWeek = scheduledTime.dayOfWeek,
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
                    message = "Disparo silenciado: Horário Silencioso / Descanso ativo"
                )
                refreshScheduleAndNotification()
                return
            }

            if (DeviceStateHelper.isPhoneCallActive(this@AudioReminderService)) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.ROUTING,
                    message = "Disparo pausado: Chamada telefônica em andamento"
                )
                refreshScheduleAndNotification()
                return
            }

            if (prefs.respectDoNotDisturb &&
                DeviceStateHelper.isDoNotDisturbActive(this@AudioReminderService)
            ) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.WARN,
                    category = DiagnosticCategory.ROUTING,
                    message = "Disparo silenciado: Modo Não Perturbe do sistema ativo"
                )
                refreshScheduleAndNotification()
                return
            }

            val dueTriggers = ScheduleCalculator.getTriggersAt(
                triggerTime = scheduledTime,
                profiles = profiles,
                activeAudios = activeAudios,
                lastSequentialIndex = prefs.lastSequentialIndex,
                quietHoursEnabled = prefs.quietHoursEnabled,
                quietHoursStartHour = prefs.quietHoursStartHour,
                quietHoursStartMinute = prefs.quietHoursStartMinute,
                quietHoursEndHour = prefs.quietHoursEndHour,
                quietHoursEndMinute = prefs.quietHoursEndMinute,
                quietHoursDaysCsv = prefs.quietHoursDaysCsv
            )
            val dueProfiles = dueTriggers
                .mapNotNull { trigger -> profiles.firstOrNull { it.id == trigger.profileId } }
                .distinctBy { it.id }
                .sortedBy { it.id }

            if (dueProfiles.isEmpty()) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.INFO,
                    category = DiagnosticCategory.ALARM,
                    message = "Nenhum agendamento independente corresponde ao horário ${scheduledTime.format(timeFormatter)}"
                )
                refreshScheduleAndNotification()
                return
            }

            for (profile in dueProfiles) {
                val selectedAudio = selectAudioForPlayback(
                    profile = profile,
                    activeAudios = activeAudios,
                    lastSeqIndex = prefs.lastSequentialIndex
                )
                if (selectedAudio == null) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.WARN,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Perfil sem áudio elegível: ${profile.name}"
                    )
                    continue
                }

                AudioDiagnosticLogger.logAlarmTriggered(
                    expectedMillis = expectedMillis,
                    actualMillis = nowMillis,
                    profileName = profile.name
                )

                val playbackDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                try {
                    playAudioWithSettings(
                        audio = selectedAudio,
                        profile = profile,
                        prefs = prefs,
                        onPlaybackComplete = { playbackDone.complete(Unit) }
                    )
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                        playbackDone.await()
                    }
                } catch (playbackError: Exception) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.ERROR,
                        category = DiagnosticCategory.PLAYBACK,
                        message = "Falha ao reproduzir o lembrete ${profile.name}",
                        details = playbackError.stackTraceToString()
                    )
                }
            }

            refreshScheduleAndNotification()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.ERROR,
                category = DiagnosticCategory.SERVICE,
                message = "Erro ao processar o disparo; tentando preservar o próximo alarme",
                details = error.stackTraceToString()
            )
            try {
                refreshScheduleAndNotification()
            } catch (scheduleError: Exception) {
                AudioDiagnosticLogger.log(
                    level = DiagnosticLevel.ERROR,
                    category = DiagnosticCategory.ALARM,
                    message = "Não foi possível recuperar o agendamento após uma falha",
                    details = scheduleError.message
                )
            }
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun selectAudioForPlayback(
        profile: Profile,
        activeAudios: List<AudioItem>,
        lastSeqIndex: Int
    ): AudioItem? {
        val eligibleAudios = if (profile.audioIds.isNotEmpty()) {
            activeAudios.filter { profile.audioIds.contains(it.id) }
        } else {
            activeAudios
        }
        if (eligibleAudios.isEmpty()) return null

        return when (profile.playbackOrder) {
            PlaybackOrder.SPECIFIC -> {
                eligibleAudios.firstOrNull { it.id == profile.specificAudioId } ?: eligibleAudios.first()
            }
            PlaybackOrder.RANDOM -> {
                ScheduleCalculator.pickWeightedRandomAudio(eligibleAudios)
            }
            PlaybackOrder.SEQUENTIAL -> {
                val index = (lastSeqIndex % eligibleAudios.size + eligibleAudios.size) % eligibleAudios.size
                preferencesRepo.incrementSequentialIndex()
                eligibleAudios[index]
            }
        }
    }

    private fun playAudioWithSettings(
        audio: AudioItem,
        profile: Profile?,
        prefs: com.example.data.preferences.UserPreferences,
        onPlaybackComplete: () -> Unit = {}
    ) {
        val targetVolume = (prefs.masterVolumePercent * audio.volumePercent) / 10000f

        val fadeInEnabled = if (profile?.fadeInOverrideEnabled == true) true else prefs.fadeInEnabled
        val fadeInSeconds = if (profile?.fadeInOverrideEnabled == true) {
            profile.fadeInDurationSeconds
        } else {
            prefs.fadeInDurationSeconds
        }
        val fadeInDurationMs = if (fadeInEnabled) (fadeInSeconds * 1000).toLong() else 0L

        if (prefs.vibrateWithAudio) {
            DeviceStateHelper.triggerVibration(this)
        }

        if (prefs.showLabelInNotification) {
            com.example.util.ReminderNotificationHelper.showTriggerVisualAlert(
                context = this,
                title = "Lembrete: ${audio.label}",
                message = if (audio.ttsText.isNullOrBlank()) "Lembrete em reprodução nos fones / alto-falante." else audio.ttsText,
                reminderId = audio.id
            )
        }

        playerManager.playAudio(
            uriString = audio.uriString,
            targetVolume = targetVolume,
            fadeInDurationMs = fadeInDurationMs,
            useAppVolumeOnly = prefs.useAppVolumeOnly,
            audioTitle = audio.label,
            ttsText = audio.ttsText,
            onComplete = onPlaybackComplete
        )
    }

    private suspend fun refreshScheduleAndNotification() = withContext(Dispatchers.Main) {
        val prefs = preferencesRepo.getPreferences()
        if (!prefs.isAppMasterEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@withContext
        }

        val now = ZonedDateTime.now()
        val nowMillis = System.currentTimeMillis()
        val storedProfiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
        val storedAudios = database.audioDao().getActiveAudios().map { it.toDomain() }
        val activeReminders = database.reminderDao().getActiveReminders()
        val scheduleData = ReminderScheduleAdapter.withLegacyReminders(
            profiles = storedProfiles,
            audios = storedAudios,
            reminders = activeReminders
        )
        val profiles = scheduleData.profiles
        val activeAudios = scheduleData.audios

        val snoozeUntilZoned = if (prefs.snoozeUntilEpochMillis > nowMillis) {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(prefs.snoozeUntilEpochMillis), ZoneId.systemDefault())
        } else null

        val nextTrigger = ScheduleCalculator.getNextTrigger(
            now = now,
            profiles = profiles,
            activeAudios = activeAudios,
            snoozeUntil = snoozeUntilZoned,
            lastSequentialIndex = prefs.lastSequentialIndex,
            quietHoursEnabled = prefs.quietHoursEnabled,
            quietHoursStartHour = prefs.quietHoursStartHour,
            quietHoursStartMinute = prefs.quietHoursStartMinute,
            quietHoursEndHour = prefs.quietHoursEndHour,
            quietHoursEndMinute = prefs.quietHoursEndMinute,
            quietHoursDaysCsv = prefs.quietHoursDaysCsv
        )

        val isSnoozed = snoozeUntilZoned != null
        val activeProfile = ScheduleCalculator.findActiveProfileFor(now, profiles)
        val inRest = activeProfile?.let {
            val rests = ScheduleCalculator.mergeRestingPeriods(it.restingPeriods)
            ScheduleCalculator.findCoveringRest(now.toLocalTime(), rests)
        }

        val statusText = when {
            isSnoozed -> "Silenciado até ${snoozeUntilZoned?.format(timeFormatter)}"
            inRest != null -> "Em descanso até ${inRest.endTime.format(timeFormatter)}"
            nextTrigger != null -> "Próximo áudio às ${nextTrigger.triggerTime.format(timeFormatter)}"
            activeAudios.isEmpty() -> "Nenhum áudio ativo na biblioteca"
            activeProfile == null -> "Nenhum perfil ativo no momento"
            else -> "Aguardando próximo horário"
        }

        try {
            val notification = buildForegroundNotification(statusText, isSnoozed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        if (nextTrigger != null) {
            scheduleExactAlarm(nextTrigger.triggerTime)
        } else {
            cancelExistingAlarm()
        }
    }

    private fun scheduleExactAlarm(triggerTime: ZonedDateTime) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerMillis = triggerTime.toInstant().toEpochMilli()
        val triggerTimeStr = triggerTime.format(timeFormatter)

        val intent = Intent(this, BootAndAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_EXPECTED_TIME_MILLIS, triggerMillis)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = DeviceStateHelper.canScheduleExactAlarms(this)
        var usedMethod = "Desconhecido"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerMillis,
                        pendingIntent
                    )
                    usedMethod = "setExactAndAllowWhileIdle (Exato)"
                } else {
                    // Fallback usando AlarmClock (garante despertar mesmo sem permissão explícita de alarme exato)
                    val showIntent = PendingIntent.getActivity(
                        this,
                        2002,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMillis, showIntent)
                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                    usedMethod = "setAlarmClock (Alarme Relógio Prioritário)"
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
                usedMethod = "setExactAndAllowWhileIdle"
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
                usedMethod = "setExact"
            }

            AudioDiagnosticLogger.logAlarmScheduled(
                triggerTimeStr = triggerTimeStr,
                triggerMillis = triggerMillis,
                method = usedMethod,
                canScheduleExact = canExact
            )
        } catch (e: Throwable) {
            AudioDiagnosticLogger.log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.ALARM,
                message = "Falha no agendamento primário, acionando fallback prioritário: ${e.message}",
                details = "Método tentado: $usedMethod"
            )
            try {
                // Tenta prioritariamente AlarmClock (garante despertar e isenção de serviço em 1º plano no Android 12+)
                val showIntent = PendingIntent.getActivity(
                    this,
                    2002,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMillis, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                AudioDiagnosticLogger.logAlarmScheduled(
                    triggerTimeStr = triggerTimeStr,
                    triggerMillis = triggerMillis,
                    method = "setAlarmClock (Fallback Prioritário)",
                    canScheduleExact = true
                )
            } catch (fallbackError: Throwable) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerMillis,
                            pendingIntent
                        )
                    }
                    AudioDiagnosticLogger.logAlarmScheduled(
                        triggerTimeStr = triggerTimeStr,
                        triggerMillis = triggerMillis,
                        method = "setAndAllowWhileIdle (Aproximado)",
                        canScheduleExact = false
                    )
                } catch (t: Throwable) {
                    AudioDiagnosticLogger.log(
                        level = DiagnosticLevel.ERROR,
                        category = DiagnosticCategory.ALARM,
                        message = "Falha crítica ao agendar alarme no sistema: ${t.message}"
                    )
                }
            }
        }
    }

    private fun cancelExistingAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, BootAndAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            2001,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            try {
                alarmManager.cancel(pendingIntent)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun buildForegroundNotification(statusText: String, isSnoozed: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioReminderService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Lembretes em Áudio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isSnoozed) {
            val resumeIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, AudioReminderService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Retomar", resumeIntent)
        } else {
            val snooze1hIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, AudioReminderService::class.java).apply {
                    action = ACTION_SNOOZE
                    putExtra(EXTRA_SNOOZE_MINUTES, 60)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_idle_alarm, "Silenciar 1h", snooze1hIntent)
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar tudo", stopIntent)

        return builder.build()
    }

    private fun showAlertNotification(title: String, message: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("extra_opened_from_notification", true)
            }
            val openPendingIntent = PendingIntent.getActivity(
                this,
                401,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(openPendingIntent, false)
                .setVibrate(longArrayOf(0, 400, 200, 400))
                .build()

            notificationManager.notify(NOTIFICATION_ID_ALERT, notification)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Status do Serviço",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente de monitoramento dos lembretes"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Avisos de Lembretes (Alta Prioridade)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de lembretes tocando, ignorados ou perdidos"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertsChannel)

            // Garante o canal de alertas visuais intrusivos de disparos
            com.example.util.ReminderNotificationHelper.ensureNotificationChannel(this)
        }
    }

    override fun onDestroy() {
        playerManager.stop()
        alarmTriggerChannel.close()
        serviceJob.cancel()
        super.onDestroy()
    }
}
