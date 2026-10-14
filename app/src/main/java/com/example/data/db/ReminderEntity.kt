package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val text: String = "",
    val audioUri: String? = null,
    val intervalMinutes: Int = 15,
    val isEnabled: Boolean = true,
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 22,
    val endMinute: Int = 0,
    val daysOfWeekCsv: String = "1,2,3,4,5,6,7",
    val dateCreated: Long = System.currentTimeMillis()
)
