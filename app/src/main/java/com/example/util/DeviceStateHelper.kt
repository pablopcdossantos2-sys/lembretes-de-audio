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
import android.telephony.TelephonyManager

object DeviceStateHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: true
        } else {
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
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val isModeInCall = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

        @Suppress("DEPRECATION")
        val isCallStateActive = telephonyManager?.callState != TelephonyManager.CALL_STATE_IDLE

        return isModeInCall || isCallStateActive
    }

    fun isBluetoothAudioConnected(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    fun isHeadphonesConnected(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    fun getConnectedAudioDevicesDescription(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Indisponível"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val names = devices.mapNotNull { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Fone Bluetooth (${device.productName.ifBlank { "Sem nome" }})"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Fone com Fio (P2 / P3)"
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "Fone / Áudio USB"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Alto-falante Embutido"
                    else -> null
                }
            }.distinct()

            return if (names.isEmpty()) "Alto-falante Padrão do Celular" else names.joinToString(", ")
        } else {
            val isHeadset = @Suppress("DEPRECATION") audioManager.isWiredHeadsetOn
            val isBt = @Suppress("DEPRECATION") (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn)
            return when {
                isBt -> "Fone Bluetooth Conectado"
                isHeadset -> "Fone com Fio Conectado"
                else -> "Alto-falante do Celular"
            }
        }
    }

    fun isDoNotDisturbActive(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val currentFilter = notificationManager.currentInterruptionFilter
        return currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                currentFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
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
