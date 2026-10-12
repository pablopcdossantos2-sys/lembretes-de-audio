package com.example.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import java.time.DayOfWeek
import java.time.LocalTime

import com.example.domain.model.AudioType

@Entity(tableName = "audios")
data class AudioItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val label: String,
    val weight: Int = 3,
    val volumePercent: Int = 100,
    val isActive: Boolean = true,
    val audioType: String = AudioType.AUDIO_FILE.name,
    val ttsText: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
) {
    fun toDomain(isMissing: Boolean = false): AudioItem {
        val type = try {
            AudioType.valueOf(audioType)
        } catch (_: Exception) {
            AudioType.AUDIO_FILE
        }
        return AudioItem(
            id = id,
            uriString = uriString,
            label = label,
            weight = weight,
            volumePercent = volumePercent,
            isActive = isActive,
            isMissing = isMissing,
            audioType = type,
            ttsText = ttsText,
            dateAdded = dateAdded
        )
    }

    companion object {
        fun fromDomain(domain: AudioItem): AudioItemEntity {
            return AudioItemEntity(
                id = domain.id,
                uriString = domain.uriString,
                label = domain.label,
                weight = domain.weight,
                volumePercent = domain.volumePercent,
                isActive = domain.isActive,
                audioType = domain.audioType.name,
                ttsText = domain.ttsText,
                dateAdded = domain.dateAdded
            )
        }
    }
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val daysOfWeekCsv: String = "1,2,3,4,5", // 1=Monday .. 7=Sunday
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 22,
    val endMinute: Int = 0,
    val intervalMinutes: Int = 15,
    val playbackOrder: String = PlaybackOrder.SEQUENTIAL.name,
    val specificAudioId: Long? = null,
    val audioIdsCsv: String = "", // Empty means all audios
    val resumePolicy: String = ResumePolicy.NEXT_GRID_MARK.name,
    val fadeInOverrideEnabled: Boolean = false,
    val fadeInDurationSeconds: Float = 2.0f
)

@Entity(
    tableName = "resting_periods",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["profileId"])]
)
data class RestingPeriodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val label: String = "",
    val startHour: Int = 12,
    val startMinute: Int = 0,
    val endHour: Int = 13,
    val endMinute: Int = 0
) {
    fun toDomain(): RestingPeriod {
        return RestingPeriod(
            id = id,
            label = label,
            startTime = LocalTime.of(startHour, startMinute),
            endTime = LocalTime.of(endHour, endMinute)
        )
    }

    companion object {
        fun fromDomain(domain: RestingPeriod, profileId: Long): RestingPeriodEntity {
            return RestingPeriodEntity(
                id = domain.id,
                profileId = profileId,
                label = domain.label,
                startHour = domain.startTime.hour,
                startMinute = domain.startTime.minute,
                endHour = domain.endTime.hour,
                endMinute = domain.endTime.minute
            )
        }
    }
}

data class ProfileWithRestingPeriods(
    @Embedded val profile: ProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "profileId"
    )
    val rests: List<RestingPeriodEntity>
) {
    fun toDomain(): Profile {
        val days = if (profile.daysOfWeekCsv.isBlank()) {
            emptySet()
        } else {
            profile.daysOfWeekCsv.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { DayOfWeek.of(it) }
                .toSet()
        }

        val audios = if (profile.audioIdsCsv.isBlank()) {
            emptyList()
        } else {
            profile.audioIdsCsv.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
        }

        return Profile(
            id = profile.id,
            name = profile.name,
            isEnabled = profile.isEnabled,
            priority = profile.priority,
            daysOfWeek = days,
            startTime = LocalTime.of(profile.startHour, profile.startMinute),
            endTime = LocalTime.of(profile.endHour, profile.endMinute),
            intervalMinutes = profile.intervalMinutes,
            playbackOrder = try {
                PlaybackOrder.valueOf(profile.playbackOrder)
            } catch (_: Exception) {
                PlaybackOrder.SEQUENTIAL
            },
            specificAudioId = profile.specificAudioId,
            audioIds = audios,
            restingPeriods = rests.map { it.toDomain() },
            resumePolicy = try {
                ResumePolicy.valueOf(profile.resumePolicy)
            } catch (_: Exception) {
                ResumePolicy.NEXT_GRID_MARK
            },
            fadeInOverrideEnabled = profile.fadeInOverrideEnabled,
            fadeInDurationSeconds = profile.fadeInDurationSeconds
        )
    }

    companion object {
        fun fromDomain(domain: Profile): Pair<ProfileEntity, List<RestingPeriodEntity>> {
            val entity = ProfileEntity(
                id = domain.id,
                name = domain.name,
                isEnabled = domain.isEnabled,
                priority = domain.priority,
                daysOfWeekCsv = domain.daysOfWeek.map { it.value }.joinToString(","),
                startHour = domain.startTime.hour,
                startMinute = domain.startTime.minute,
                endHour = domain.endTime.hour,
                endMinute = domain.endTime.minute,
                intervalMinutes = domain.intervalMinutes,
                playbackOrder = domain.playbackOrder.name,
                specificAudioId = domain.specificAudioId,
                audioIdsCsv = domain.audioIds.joinToString(","),
                resumePolicy = domain.resumePolicy.name,
                fadeInOverrideEnabled = domain.fadeInOverrideEnabled,
                fadeInDurationSeconds = domain.fadeInDurationSeconds
            )
            val rests = domain.restingPeriods.map {
                RestingPeriodEntity.fromDomain(it, domain.id)
            }
            return Pair(entity, rests)
        }
    }
}

/**
 * Entidade Room para armazenar lembretes (texto, áudio/URI e periodicidade)
 * diretamente no banco de dados SQLite local do dispositivo.
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val text: String? = null,
    val audioUri: String? = null,
    val periodicityMinutes: Int = 15,
    val daysOfWeekCsv: String = "1,2,3,4,5,6,7", // 1=Segunda .. 7=Domingo
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 22,
    val endMinute: Int = 0,
    val isEnabled: Boolean = true,
    val audioType: String = AudioType.AUDIO_FILE.name,
    val volumePercent: Int = 100,
    val createdAt: Long = System.currentTimeMillis()
)

typealias LembreteEntity = ReminderEntity

