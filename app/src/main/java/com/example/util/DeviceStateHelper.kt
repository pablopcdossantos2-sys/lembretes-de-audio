package com.example.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

object DeviceStateHelper {

    // Constantes de tipos de áudio adicionados em versões mais recentes do Android (evita NoSuchFieldError em APIs < 31)
    private const val TYPE_BLE_HEADSET_VAL = 26
    private const val TYPE_BLE_SPEAKER_VAL = 27
    private const val TYPE_BLE_BROADCAST_VAL = 30
    private const val TYPE_USB_HEADSET_VAL = 22
    private const val TYPE_USB_DEVICE_VAL = 21

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() ?: true
            } else {
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Abre a tela de configurações do sistema para o usuário conceder
     * a permissão de agendamento de alarmes exatos (SCHEDULE_EXACT_ALARM) no Android 12+ (API 31+).
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    try {
                        val appDetailsIntent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(appDetailsIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun isPhoneCallActive(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
        } catch (_: Throwable) {
            false
        }
    }

    fun isBluetoothAudioConnected(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
                devices.any { device ->
                    val type = device.type
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            type == TYPE_BLE_HEADSET_VAL ||
                            type == TYPE_BLE_SPEAKER_VAL ||
                            type == TYPE_BLE_BROADCAST_VAL
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun isHeadphonesConnected(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
                devices.any { device ->
                    val type = device.type
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            type == TYPE_BLE_HEADSET_VAL ||
                            type == TYPE_BLE_SPEAKER_VAL ||
                            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            type == TYPE_USB_HEADSET_VAL ||
                            type == TYPE_USB_DEVICE_VAL
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun getConnectedAudioDevicesDescription(context: Context): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Alto-falante do Celular"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { null } ?: emptyArray()
                val names = devices.mapNotNull { device ->
                    if (device == null) return@mapNotNull null
                    val rawName = try { device.productName?.toString()?.trim() } catch (_: Throwable) { null }
                    val label = if (!rawName.isNullOrBlank()) rawName else "Sem nome"
                    val devType = try { device.type } catch (_: Throwable) { -1 }
                    when (devType) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        TYPE_BLE_HEADSET_VAL,
                        TYPE_BLE_SPEAKER_VAL,
                        TYPE_BLE_BROADCAST_VAL -> "Fone Bluetooth ($label)"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Fone com Fio (P2 / P3)"
                        TYPE_USB_HEADSET_VAL,
                        TYPE_USB_DEVICE_VAL -> "Fone / Áudio USB"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Alto-falante Embutido"
                        else -> null
                    }
                }.distinct()

                if (names.isEmpty()) "Alto-falante Padrão do Celular" else names.joinToString(", ")
            } else {
                val isHeadset = @Suppress("DEPRECATION") try { audioManager.isWiredHeadsetOn } catch (_: Throwable) { false }
                val isBt = @Suppress("DEPRECATION") try { audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn } catch (_: Throwable) { false }
                when {
                    isBt -> "Fone Bluetooth Conectado"
                    isHeadset -> "Fone com Fio Conectado"
                    else -> "Alto-falante do Celular"
                }
            }
        } catch (_: Throwable) {
            "Alto-falante do Celular"
        }
    }

    fun isDoNotDisturbActive(context: Context): Boolean {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val currentFilter = notificationManager.currentInterruptionFilter
            currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    currentFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (_: Throwable) {
            false
        }
    }

    fun triggerVibration(context: Context, durationMs: Long = 400L) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {}
    }
}
