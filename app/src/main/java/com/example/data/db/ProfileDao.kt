package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Transaction
    @Query("SELECT * FROM profiles ORDER BY priority DESC, id ASC")
    fun getAllProfilesWithRestsFlow(): Flow<List<ProfileWithRestingPeriods>>

    @Transaction
    @Query("SELECT * FROM profiles ORDER BY priority DESC, id ASC")
    suspend fun getAllProfilesWithRests(): List<ProfileWithRestingPeriods>

    @Transaction
    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileWithRestsById(id: Long): ProfileWithRestingPeriods?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("UPDATE profiles SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setProfileEnabled(id: Long, isEnabled: Boolean)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Query("DELETE FROM profiles")
    suspend fun deleteAllProfiles()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestingPeriods(restingPeriods: List<RestingPeriodEntity>)

    @Query("DELETE FROM resting_periods WHERE profileId = :profileId")
    suspend fun deleteRestingPeriodsForProfile(profileId: Long)

    @Transaction
    suspend fun upsertProfileWithRests(profile: ProfileEntity, rests: List<RestingPeriodEntity>): Long {
        val profileId = insertProfile(profile)
        deleteRestingPeriodsForProfile(profileId)
        if (rests.isNotEmpty()) {
            val restsWithId = rests.map { it.copy(profileId = profileId) }
            insertRestingPeriods(restsWithId)
        }
        return profileId
    }
}
