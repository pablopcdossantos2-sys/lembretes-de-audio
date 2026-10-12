package com.example.domain.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

enum class PlaybackOrder {
    SEQUENTIAL,
    RANDOM,
    SPECIFIC
}

enum class ResumePolicy {
    NEXT_GRID_MARK,
    AT_REST_END
}

data class RestingPeriod(
    val id: Long = 0,
    val label: String = "",
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    /**
     * Checks if this rest period crosses midnight (e.g. 22:00 to 07:00).
     */
    val crossesMidnight: Boolean
        get() = startTime.isAfter(endTime) || startTime == endTime

    /**
     * Checks if a specific LocalTime is strictly within the resting period.
     * Rule: if trigger lands exactly at startTime, it does NOT play.
     * If trigger lands exactly at endTime, rest is finished, so it is allowed to play.
     */
    fun contains(time: LocalTime): Boolean {
        return if (crossesMidnight) {
            // e.g. 22:00 to 07:00: active from 22:00..23:59:59 OR 00:00..06:59:59
            !time.isBefore(startTime) || time.isBefore(endTime)
        } else {
            !time.isBefore(startTime) && time.isBefore(endTime)
        }
    }
}

enum class AudioType {
    AUDIO_FILE,
    RECORDING,
    TEXT_TO_SPEECH
}

data class AudioItem(
    val id: Long = 0,
    val uriString: String,
    val label: String,
    val weight: Int = 3,
    val volumePercent: Int = 100,
    val isActive: Boolean = true,
    val isMissing: Boolean = false,
    val audioType: AudioType = AudioType.AUDIO_FILE,
    val ttsText: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
)

data class Profile(
    val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val daysOfWeek: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    ),
    val startTime: LocalTime = LocalTime.of(8, 0),
    val endTime: LocalTime = LocalTime.of(22, 0),
    val intervalMinutes: Int = 15,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENTIAL,
    val specificAudioId: Long? = null,
    val audioIds: List<Long> = emptyList(), // Empty means all active audios
    val restingPeriods: List<RestingPeriod> = emptyList(),
    val resumePolicy: ResumePolicy = ResumePolicy.NEXT_GRID_MARK,
    val fadeInOverrideEnabled: Boolean = false,
    val fadeInDurationSeconds: Float = 2.0f
) {
    val crossesMidnight: Boolean
        get() = startTime.isAfter(endTime)

    /**
     * Checks if a given LocalTime is inside the profile's operating window.
     */
    fun isWithinOperatingHours(time: LocalTime): Boolean {
        return if (crossesMidnight) {
            !time.isBefore(startTime) || !time.isAfter(endTime)
        } else {
            !time.isBefore(startTime) && !time.isAfter(endTime)
        }
    }
}

data class ScheduledTrigger(
    val triggerTime: ZonedDateTime,
    val profileId: Long,
    val profileName: String,
    val predictedAudioId: Long?,
    val predictedAudioLabel: String,
    val isRandomAudio: Boolean
)
