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
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.ScheduledTrigger
import com.example.domain.scheduler.ScheduleCalculator
import com.example.player.AudioPlayerManager
import com.example.receiver.BootAndAlarmReceiver
import com.example.util.DeviceStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        preferencesRepo = UserPreferencesRepository(this)
        playerManager = AudioPlayerManager(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
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
                serviceScope.launch {
                    val snoozeUntil = System.currentTimeMillis() + (minutes * 60 * 1000L)
                    preferencesRepo.setSnoozeUntilEpochMillis(snoozeUntil)
                    playerManager.stop()
                    refreshScheduleAndNotification()
                }
            }
            ACTION_RESUME -> {
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
        serviceScope.launch {
            val prefs = preferencesRepo.getPreferences()
            if (!prefs.isAppMasterEnabled) {
                stopSelf()
                return@launch
            }

            val now = ZonedDateTime.now()
            val nowMillis = System.currentTimeMillis()

            // 1. Check if missed (> 2 minutes behind expected trigger time)
            if (expectedMillis > 0 && (nowMillis - expectedMillis) > 120_000L) {
                val missedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(expectedMillis), ZoneId.systemDefault())
                showAlertNotification(
                    title = "Lembrete perdido",
                    message = "Lembrete perdido às ${missedTime.format(timeFormatter)}"
                )
                refreshScheduleAndNotification()
                return@launch
            }

            // 2. Check if currently snoozed
            if (prefs.snoozeUntilEpochMillis > nowMillis) {
                refreshScheduleAndNotification()
                return@launch
            }

            // 3. Load active profiles and audios
            val profiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
            val activeAudios = database.audioDao().getActiveAudios().map { it.toDomain() }

            if (activeAudios.isEmpty()) {
                showAlertNotification(
                    title = "Nenhum áudio ativo",
                    message = "Adicione áudios ativos para tocar lembretes."
                )
                refreshScheduleAndNotification()
                return@launch
            }

            val activeProfile = ScheduleCalculator.findActiveProfileFor(now, profiles)
            if (activeProfile == null) {
                refreshScheduleAndNotification()
                return@launch
            }

            // 4. Check if currently in rest period
            val mergedRests = ScheduleCalculator.mergeRestingPeriods(activeProfile.restingPeriods)
            val currentLocalTime = now.toLocalTime()
            if (ScheduleCalculator.isTimeInRestingPeriod(currentLocalTime, mergedRests)) {
                refreshScheduleAndNotification()
                return@launch
            }

            // 5. Check phone call
            if (DeviceStateHelper.isPhoneCallActive(this@AudioReminderService)) {
                showAlertNotification(
                    title = "Lembrete ignorado",
                    message = "Lembrete ignorado: em chamada"
                )
                refreshScheduleAndNotification()
                return@launch
            }

            // 6. Check Bluetooth audio devices
            if (DeviceStateHelper.isBluetoothAudioConnected(this@AudioReminderService)) {
                showAlertNotification(
                    title = "Lembrete ignorado",
                    message = "Lembrete ignorado: fone Bluetooth conectado"
                )
                refreshScheduleAndNotification()
                return@launch
            }

            // 7. Check Do Not Disturb
            if (prefs.respectDoNotDisturb && DeviceStateHelper.isDoNotDisturbActive(this@AudioReminderService)) {
                showAlertNotification(
                    title = "Lembrete ignorado",
                    message = "Lembrete ignorado: modo Não perturbe ativo"
                )
                refreshScheduleAndNotification()
                return@launch
            }

            // 8. Select audio to play
            val selectedAudio = selectAudioForPlayback(activeProfile, activeAudios, prefs.lastSequentialIndex)

            if (selectedAudio != null) {
                playAudioWithSettings(selectedAudio, activeProfile, prefs)
            }

            // Schedule the NEXT alarm immediately
            refreshScheduleAndNotification()
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
        profile: Profile,
        prefs: com.example.data.preferences.UserPreferences
    ) {
        val targetVolume = (prefs.masterVolumePercent * audio.volumePercent) / 10000f

        val fadeInEnabled = if (profile.fadeInOverrideEnabled) true else prefs.fadeInEnabled
        val fadeInSeconds = if (profile.fadeInOverrideEnabled) {
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
                title = "Tocando agora: ${audio.label}",
                message = if (audio.ttsText.isNullOrBlank()) "Lembrete sonoro em reprodução." else audio.ttsText,
                reminderId = audio.id
            )
        }

        playerManager.playAudio(
            uriString = audio.uriString,
            targetVolume = targetVolume,
            fadeInDurationMs = fadeInDurationMs,
            onComplete = {}
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
        val profiles = database.profileDao().getAllProfilesWithRests().map { it.toDomain() }
        val activeAudios = database.audioDao().getActiveAudios().map { it.toDomain() }

        val snoozeUntilZoned = if (prefs.snoozeUntilEpochMillis > nowMillis) {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(prefs.snoozeUntilEpochMillis), ZoneId.systemDefault())
        } else null

        val nextTrigger = ScheduleCalculator.getNextTrigger(
            now = now,
            profiles = profiles,
            activeAudios = activeAudios,
            snoozeUntil = snoozeUntilZoned,
            lastSequentialIndex = prefs.lastSequentialIndex
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
            }
        } catch (e: Throwable) {
            e.printStackTrace()
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
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
                "Avisos de Lembretes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisos de lembretes tocando, ignorados ou perdidos"
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    override fun onDestroy() {
        playerManager.stop()
        serviceJob.cancel()
        super.onDestroy()
    }
}
