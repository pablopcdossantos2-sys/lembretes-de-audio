package com.example.domain.model

data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val profiles: List<ProfileBackupDto> = emptyList(),
    val audios: List<AudioBackupDto> = emptyList(),
    val reminders: List<ReminderBackupDto> = emptyList(),
    val preferences: PreferencesBackupDto = PreferencesBackupDto()
)

data class ProfileBackupDto(
    val id: Long,
    val name: String,
    val isEnabled: Boolean,
    val priority: Int,
    val daysOfWeek: List<Int>,
    val startTime: String,
    val endTime: String,
    val intervalMinutes: Int,
    val playbackOrder: String,
    val specificAudioId: Long? = null,
    val audioIds: List<Long> = emptyList(),
    val restingPeriods: List<RestingPeriodBackupDto> = emptyList(),
    val resumePolicy: String = "NEXT_GRID_MARK",
    val fadeInOverrideEnabled: Boolean = false,
    val fadeInDurationSeconds: Float = 2.0f
)

data class RestingPeriodBackupDto(
    val id: Long,
    val label: String,
    val startTime: String,
    val endTime: String
)

data class AudioBackupDto(
    val id: Long,
    val uriString: String,
    val label: String,
    val weight: Int = 3,
    val volumePercent: Int = 100,
    val isActive: Boolean = true,
    val audioType: String = "AUDIO_FILE",
    val ttsText: String? = null,
    val dateAdded: Long = 0L,
    val audioBase64: String? = null
)

data class ReminderBackupDto(
    val id: Long,
    val title: String,
    val text: String? = null,
    val audioUri: String? = null,
    val periodicityMinutes: Int = 15,
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 20,
    val endMinute: Int = 0,
    val isEnabled: Boolean = true,
    val audioType: String = "TEXT_TO_SPEECH",
    val volumePercent: Int = 100,
    val createdAt: Long = 0L,
    val audioBase64: String? = null
)

data class PreferencesBackupDto(
    val isAppMasterEnabled: Boolean = false,
    val masterVolumePercent: Int = 100,
    val useAppVolumeOnly: Boolean = false,
    val fadeInEnabled: Boolean = true,
    val fadeInDurationSeconds: Float = 2.0f,
    val vibrateWithAudio: Boolean = true,
    val showLabelInNotification: Boolean = true,
    val respectDoNotDisturb: Boolean = true,
    val defaultResumePolicy: String = "NEXT_GRID_MARK"
)
