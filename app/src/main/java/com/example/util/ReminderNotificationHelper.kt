package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.receiver.BootAndAlarmReceiver

/**
 * Utilitário da biblioteca de notificações do Android para exibir alertas visuais de alta prioridade
 * quando um lembrete em áudio for disparado, garantindo visibilidade total na tela de bloqueio
 * (Lockscreen) com iluminação da tela, botões de ação direta (Parar, Adiar 10m) e categoria de alarme.
 */
object ReminderNotificationHelper {

    const val CHANNEL_TRIGGER_ALERTS_ID = "audio_reminder_visual_alerts"
    const val NOTIFICATION_ID_TRIGGER_ALERT = 5001

    const val ACTION_STOP_AUDIO_ALERT = "com.example.action.STOP_AUDIO_ALERT"
    const val ACTION_SNOOZE_10_MIN_ALERT = "com.example.action.SNOOZE_10_MIN_ALERT"
    const val ACTION_DISMISS_ALERT = "com.example.action.DISMISS_ALERT"

    /**
     * Garante a criação do canal de alta prioridade com visibilidade na tela de bloqueio.
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = notificationManager.getNotificationChannel(CHANNEL_TRIGGER_ALERTS_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_TRIGGER_ALERTS_ID,
                    "Alertas Visuais de Lembretes (Tela Bloqueada)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Exibe avisos visuais imediatos mesmo com o aparelho bloqueado"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 450, 200, 450)
                    enableLights(true)
                    lightColor = Color.CYAN
                    setBypassDnd(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Ilumina temporariamente a tela quando o celular estiver bloqueado e em repouso.
     */
    fun wakeScreenIfLocked(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }

            if (!isScreenOn) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "AudioReminder:VisualAlertWakeLock"
                )
                wakeLock.acquire(4000L) // 4 segundos de tela acesa
            }
        } catch (_: Throwable) {}
    }

    /**
     * Exibe o alerta visual de lembrete com alta prioridade e visibilidade pública na tela de bloqueio.
     */
    fun showTriggerVisualAlert(
        context: Context,
        title: String,
        message: String?,
        reminderId: Long? = null
    ) {
        try {
            ensureNotificationChannel(context)
            wakeScreenIfLocked(context)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Intent para abrir o app diretamente
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("extra_opened_from_notification", true)
                reminderId?.let { putExtra("extra_reminder_id", it) }
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                501,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Ação: Parar Áudio
            val stopIntent = Intent(context, BootAndAlarmReceiver::class.java).apply {
                action = ACTION_STOP_AUDIO_ALERT
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                502,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Ação: Adiar 10 Minutos (Snooze)
            val snoozeIntent = Intent(context, BootAndAlarmReceiver::class.java).apply {
                action = ACTION_SNOOZE_10_MIN_ALERT
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                503,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val displayTitle = title.ifBlank { "Lembrete em Áudio" }
            val displayText = message?.ifBlank { null } ?: "Lembrete sonoro disparado no seu dispositivo."

            val builder = NotificationCompat.Builder(context, CHANNEL_TRIGGER_ALERTS_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(displayTitle)
                .setContentText(displayText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("🔔 $displayTitle")
                        .bigText("$displayText\n\nToque para abrir ou use os botões rápidos abaixo.")
                )
                // Visibilidade pública obrigatória para exibição com conteúdo na tela bloqueada
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                // Mantém alerta heads-up via canal de alta importância sem interromper a tela do usuário.
                .setVibrate(longArrayOf(0, 450, 200, 450))
                .setLights(Color.CYAN, 1000, 500)
                .addAction(android.R.drawable.ic_media_pause, "⏹ Parar Áudio", stopPendingIntent)
                .addAction(android.R.drawable.ic_lock_idle_alarm, "⏰ Adiar 10m", snoozePendingIntent)

            notificationManager.notify(NOTIFICATION_ID_TRIGGER_ALERT, builder.build())
        } catch (_: Throwable) {}
    }

    /**
     * Remove o alerta visual ativo ao parar ou finalizar a reprodução.
     */
    fun cancelVisualAlert(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            notificationManager.cancel(NOTIFICATION_ID_TRIGGER_ALERT)
        } catch (_: Throwable) {}
    }
}
