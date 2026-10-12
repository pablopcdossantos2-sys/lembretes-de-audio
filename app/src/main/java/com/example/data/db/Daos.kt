package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audios ORDER BY dateAdded DESC")
    fun getAllAudiosFlow(): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audios ORDER BY dateAdded DESC")
    suspend fun getAllAudios(): List<AudioItemEntity>

    @Query("SELECT * FROM audios WHERE id = :id LIMIT 1")
    suspend fun getAudioById(id: Long): AudioItemEntity?

    @Query("SELECT * FROM audios WHERE isActive = 1")
    suspend fun getActiveAudios(): List<AudioItemEntity>

    @Query("SELECT * FROM audios WHERE isActive = 1")
    fun getActiveAudiosFlow(): Flow<List<AudioItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudio(audio: AudioItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudios(audios: List<AudioItemEntity>): List<Long>

    @Update
    suspend fun updateAudio(audio: AudioItemEntity)

    @Delete
    suspend fun deleteAudio(audio: AudioItemEntity)

    @Query("DELETE FROM audios WHERE id = :id")
    suspend fun deleteAudioById(id: Long)

    @Query("DELETE FROM audios")
    suspend fun clearAll()
}

@Dao
interface ProfileDao {
    @Transaction
    @Query("SELECT * FROM profiles ORDER BY priority DESC, id ASC")
    fun getAllProfilesWithRestsFlow(): Flow<List<ProfileWithRestingPeriods>>

    @Transaction
    @Query("SELECT * FROM profiles ORDER BY priority DESC, id ASC")
    suspend fun getAllProfilesWithRests(): List<ProfileWithRestingPeriods>

    @Transaction
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileWithRestsById(id: Long): ProfileWithRestingPeriods?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestingPeriods(rests: List<RestingPeriodEntity>)

    @Query("DELETE FROM resting_periods WHERE profileId = :profileId")
    suspend fun deleteRestingPeriodsForProfile(profileId: Long)

    @Transaction
    suspend fun upsertProfileWithRests(
        profile: ProfileEntity,
        rests: List<RestingPeriodEntity>
    ): Long {
        val profileId = if (profile.id == 0L) {
            insertProfile(profile)
        } else {
            updateProfile(profile)
            profile.id
        }
        deleteRestingPeriodsForProfile(profileId)
        val updatedRests = rests.map { it.copy(profileId = profileId) }
        insertRestingPeriods(updatedRests)
        return profileId
    }

    @Query("UPDATE profiles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setProfileEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM profiles")
    suspend fun clearAllProfiles()
}

/**
 * Data Access Object (DAO) para operações no banco de dados de Lembretes.
 */
@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY createdAt DESC")
    fun getAllRemindersFlow(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY createdAt DESC")
    suspend fun getAllReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1 ORDER BY startHour ASC, startMinute ASC")
    fun getActiveRemindersFlow(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1 ORDER BY startHour ASC, startMinute ASC")
    suspend fun getActiveReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Long): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(reminders: List<ReminderEntity>): List<Long>

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    @Delete
    suspend fun deleteReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long)

    @Query("UPDATE reminders SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setReminderEnabled(id: Long, isEnabled: Boolean)

    @Query("DELETE FROM reminders")
    suspend fun clearAllReminders()
}

typealias LembreteDao = ReminderDao

