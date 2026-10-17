package com.example.data.db

import androidx.room.Embedded
import androidx.room.Relation

data class ProfileWithReminders(
    @Embedded val profile: ProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "profileId"
    )
    val reminders: List<ReminderEntity> = emptyList()
)
