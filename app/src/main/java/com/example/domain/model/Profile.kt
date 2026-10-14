package com.example.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

data class Profile(
    val id: Long = 0L,
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val startTime: LocalTime = LocalTime.of(8, 0),
    val endTime: LocalTime = LocalTime.of(18, 0),
    val intervalMinutes: Int = 15,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENTIAL,
    val specificAudioId: Long? = null,
    val audioIds: List<Long> = emptyList(),
    val restingPeriods: List<RestingPeriod> = emptyList(),
    val resumePolicy: ResumePolicy = ResumePolicy.NEXT_GRID_MARK,
    val fadeInOverrideEnabled: Boolean = false,
    val fadeInDurationSeconds: Float = 2.0f
) {
    val crossesMidnight: Boolean
        get() = startTime.isAfter(endTime)

    fun isWithinOperatingHours(time: LocalTime): Boolean {
        return if (crossesMidnight) {
            !time.isBefore(startTime) || !time.isAfter(endTime)
        } else {
            !time.isBefore(startTime) && !time.isAfter(endTime)
        }
    }
}
