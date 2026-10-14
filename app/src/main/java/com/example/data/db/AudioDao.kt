package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {

    @Query("SELECT * FROM audios ORDER BY dateAdded DESC")
    fun getAllAudiosFlow(): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audios ORDER BY dateAdded DESC")
    suspend fun getAllAudios(): List<AudioItemEntity>

    @Query("SELECT * FROM audios WHERE isActive = 1")
    suspend fun getActiveAudios(): List<AudioItemEntity>

    @Query("SELECT * FROM audios WHERE id = :id")
    suspend fun getAudioById(id: Long): AudioItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudio(audio: AudioItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudios(audios: List<AudioItemEntity>): List<Long>

    @Update
    suspend fun updateAudio(audio: AudioItemEntity)

    @Query("DELETE FROM audios WHERE id = :id")
    suspend fun deleteAudioById(id: Long)

    @Query("DELETE FROM audios")
    suspend fun deleteAllAudios()

    @Query("UPDATE audios SET isActive = :isActive WHERE id = :id")
    suspend fun setAudioActive(id: Long, isActive: Boolean)

    @Query("UPDATE audios SET isActive = :isActive")
    suspend fun setAllAudiosActive(isActive: Boolean)
}
