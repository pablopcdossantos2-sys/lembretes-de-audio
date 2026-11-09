package com.example.data.db

import android.net.Uri
import com.example.domain.model.AudioItem
import com.example.domain.model.AudioType
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime

data class ReminderScheduleData(
    val profiles: List<Profile>,
    val audios: List<AudioItem>
)

/**
 * Keeps the legacy reminders table schedulable when there is no enabled Profile.
 * Current recordings already have their own persisted Profile and do not use this path.
 */
object ReminderScheduleAdapter {

    fun withLegacyReminders(
        profiles: List<Profile>,
        audios: List<AudioItem>,
        reminders: List<ReminderEntity>
    ): ReminderScheduleData {
        if (profiles.any { it.isEnabled } || reminders.isEmpty()) {
            return ReminderScheduleData(profiles, audios)
        }

        val legacyProfiles = mutableListOf<Profile>()
        val legacyAudios = mutableListOf<AudioItem>()

        reminders.forEach { reminder ->
            val syntheticId = -reminder.id
            val file = reminder.audioFilePath
                ?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }
            val uri = file?.let { Uri.fromFile(it).toString() }
                ?: reminder.audioUri
                ?: "tts:\${reminder.title}"

            val audioType = if (uri.startsWith("tts:")) {
                AudioType.TEXT_TO_SPEECH
            } else {
                AudioType.AUDIO_FILE
            }
            legacyAudios += AudioItem(
                id = syntheticId,
                uriString = uri,
                label = reminder.title,
                weight = 4,
                volumePercent = 100,
                isActive = true,
                audioType = audioType,
                ttsText = reminder.text.takeIf { it.isNotBlank() }
            )

            val days = reminder.daysOfWeekCsv.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .mapNotNull { day ->
                    runCatching { DayOfWeek.of(day) }.getOrNull()
                }.toSet().ifEmpty { DayOfWeek.values().toSet() }

            val rests = if (
                reminder.hasRestPeriod &&
                (reminder.startHour != reminder.endHour ||
                    reminder.startMinute != reminder.endMinute)
            ) {
                listOf(
                    RestingPeriod(
                        label = "Descanso",
                        startTime = LocalTime.of(
                            reminder.startHour.coerceIn(0, 23),
                            reminder.startMinute.coerceIn(0, 59)
                        ),
                        endTime = LocalTime.of(
                            reminder.endHour.coerceIn(0, 23),
                            reminder.endMinute.coerceIn(0, 59)
                        )
                    )
                )
            } else {
                emptyList()
            }

            legacyProfiles += Profile(
                id = syntheticId,
                name = reminder.title,
                isEnabled = reminder.isEnabled,
                priority = 20,
                daysOfWeek = days,
                startTime = LocalTime.MIDNIGHT,
                endTime = LocalTime.of(23, 59),
                intervalMinutes = reminder.intervalMinutes.coerceIn(5, 120),
                playbackOrder = PlaybackOrder.SPECIFIC,
                specificAudioId = syntheticId,
                audioIds = listOf(syntheticId),
                restingPeriods = rests,
                resumePolicy = ResumePolicy.NEXT_GRID_MARK
            )
        }

        return ReminderScheduleData(
            profiles = profiles.filter { it.isEnabled } + legacyProfiles,
            audios = audios + legacyAudios
        )
    }
}
