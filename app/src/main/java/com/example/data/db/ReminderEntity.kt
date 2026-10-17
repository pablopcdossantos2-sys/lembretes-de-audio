package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
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
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val profileId: Long? = null,
    val title: String,
    val text: String = "",
    val audioFilePath: String? = null,
    val audioUri: String? = null,
    val intervalMinutes: Int = 15,
    val isEnabled: Boolean = true,
    val hasRestPeriod: Boolean = false,
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 59,
    val daysOfWeekCsv: String = "1,2,3,4,5,6,7",
    val dateCreated: Long = System.currentTimeMillis()
) {
    val periodicityMinutes: Int get() = intervalMinutes
}
