package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val daysOfWeekCsv: String = "1,2,3,4,5,6,7",
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 18,
    val endMinute: Int = 0,
    val intervalMinutes: Int = 15,
    val playbackOrder: String = "SEQUENTIAL",
    val specificAudioId: Long? = null,
    val audioIdsCsv: String = "",
    val resumePolicy: String = "NEXT_GRID_MARK",
    val fadeInOverrideEnabled: Boolean = false,
    val fadeInDurationSeconds: Float = 2.0f
)
