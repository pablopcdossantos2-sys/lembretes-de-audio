package com.example.data.db

import androidx.room.withTransaction

data class ReminderFrequencyUpdateResult(
    val intervalMinutes: Int,
    val updatedProfiles: Int,
    val updatedReminders: Int
) {
    val applied: Boolean get() = updatedProfiles > 0 || updatedReminders > 0
}

/**
 * Keeps the frequency shown in the Library in sync with the profiles and legacy reminder
 * records that actually schedule the selected audio.
 */
object ReminderFrequencyUpdater {

    suspend fun update(
        database: AppDatabase,
        audioId: Long,
        audioUri: String,
        requestedIntervalMinutes: Int
    ): ReminderFrequencyUpdateResult {
        val interval = requestedIntervalMinutes.coerceIn(5, 120)
        var updatedProfiles = 0
        var updatedReminders = 0

        database.withTransaction {
            val profiles = database.profileDao().getAllProfilesWithRests()
            val explicitlyLinked = profiles.filter { item ->
                item.profile.specificAudioId == audioId ||
                    item.profile.audioIdsCsv
                        .split(",")
                        .mapNotNull { it.trim().toLongOrNull() }
                        .contains(audioId)
            }
            // Blank audioIdsCsv and no specificAudioId means the profile schedules all
            // library audios, so its cadence also applies to the selected audio.
            val shared = profiles.filter { item ->
                item.profile.specificAudioId == null && item.profile.audioIdsCsv.isBlank()
            }
            val targets = (explicitlyLinked + shared).distinctBy { it.profile.id }
            targets.forEach { item ->
                database.profileDao().updateProfile(
                    item.profile.copy(intervalMinutes = interval)
                )
            }
            updatedProfiles = targets.size

            val linkedReminders = database.reminderDao().getAllReminders()
                .filter { it.audioUri == audioUri }
            linkedReminders.forEach { reminder ->
                database.reminderDao().updateReminder(
                    reminder.copy(intervalMinutes = interval)
                )
            }
            updatedReminders = linkedReminders.size
        }

        return ReminderFrequencyUpdateResult(
            intervalMinutes = interval,
            updatedProfiles = updatedProfiles,
            updatedReminders = updatedReminders
        )
    }
}
