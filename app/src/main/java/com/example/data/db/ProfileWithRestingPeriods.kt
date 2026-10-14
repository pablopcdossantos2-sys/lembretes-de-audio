package com.example.data.db

import androidx.room.Embedded
import androidx.room.Relation
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.ResumePolicy
import java.time.DayOfWeek
import java.time.LocalTime

data class ProfileWithRestingPeriods(
    @Embedded val profile: ProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "profileId"
    )
    val restingPeriods: List<RestingPeriodEntity> = emptyList()
) {
    fun toDomain(): Profile {
        val days = profile.daysOfWeekCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull {
                try {
                    DayOfWeek.of(it)
                } catch (_: Exception) {
                    null
                }
            }.toSet()

        val pOrder = try {
            PlaybackOrder.valueOf(profile.playbackOrder)
        } catch (_: Exception) {
            PlaybackOrder.SEQUENTIAL
        }

        val rPolicy = try {
            ResumePolicy.valueOf(profile.resumePolicy)
        } catch (_: Exception) {
            ResumePolicy.NEXT_GRID_MARK
        }

        val aIds = profile.audioIdsCsv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }

        return Profile(
            id = profile.id,
            name = profile.name,
            isEnabled = profile.isEnabled,
            priority = profile.priority,
            daysOfWeek = days,
            startTime = LocalTime.of(profile.startHour.coerceIn(0, 23), profile.startMinute.coerceIn(0, 59)),
            endTime = LocalTime.of(profile.endHour.coerceIn(0, 23), profile.endMinute.coerceIn(0, 59)),
            intervalMinutes = profile.intervalMinutes,
            playbackOrder = pOrder,
            specificAudioId = profile.specificAudioId,
            audioIds = aIds,
            restingPeriods = restingPeriods.map { it.toDomain() },
            resumePolicy = rPolicy,
            fadeInOverrideEnabled = profile.fadeInOverrideEnabled,
            fadeInDurationSeconds = profile.fadeInDurationSeconds
        )
    }

    companion object {
        fun fromDomain(profile: Profile): Pair<ProfileEntity, List<RestingPeriodEntity>> {
            val daysCsv = profile.daysOfWeek.map { it.value }.sorted().joinToString(",")
            val audioIdsCsv = profile.audioIds.joinToString(",")
            val entity = ProfileEntity(
                id = profile.id,
                name = profile.name,
                isEnabled = profile.isEnabled,
                priority = profile.priority,
                daysOfWeekCsv = daysCsv,
                startHour = profile.startTime.hour,
                startMinute = profile.startTime.minute,
                endHour = profile.endTime.hour,
                endMinute = profile.endTime.minute,
                intervalMinutes = profile.intervalMinutes,
                playbackOrder = profile.playbackOrder.name,
                specificAudioId = profile.specificAudioId,
                audioIdsCsv = audioIdsCsv,
                resumePolicy = profile.resumePolicy.name,
                fadeInOverrideEnabled = profile.fadeInOverrideEnabled,
                fadeInDurationSeconds = profile.fadeInDurationSeconds
            )
            val rests = profile.restingPeriods.map {
                RestingPeriodEntity.fromDomain(it, profile.id)
            }
            return Pair(entity, rests)
        }
    }
}
