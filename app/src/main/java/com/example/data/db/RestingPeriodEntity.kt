package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.RestingPeriod
import java.time.LocalTime

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
    val id: Long = 0L,
    val profileId: Long = 0L,
    val label: String = "Descanso",
    val startHour: Int = 12,
    val startMinute: Int = 0,
    val endHour: Int = 13,
    val endMinute: Int = 0
) {
    fun toDomain(): RestingPeriod {
        return RestingPeriod(
            id = id,
            label = label,
            startTime = LocalTime.of(startHour.coerceIn(0, 23), startMinute.coerceIn(0, 59)),
            endTime = LocalTime.of(endHour.coerceIn(0, 23), endMinute.coerceIn(0, 59))
        )
    }

    companion object {
        fun fromDomain(restingPeriod: RestingPeriod, profileId: Long): RestingPeriodEntity {
            return RestingPeriodEntity(
                id = restingPeriod.id,
                profileId = profileId,
                label = restingPeriod.label,
                startHour = restingPeriod.startTime.hour,
                startMinute = restingPeriod.startTime.minute,
                endHour = restingPeriod.endTime.hour,
                endMinute = restingPeriod.endTime.minute
            )
        }
    }
}
