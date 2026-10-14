package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AudioItem
import com.example.domain.model.AudioType

@Entity(tableName = "audios")
data class AudioItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
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
        fun fromDomain(audio: AudioItem): AudioItemEntity {
            return AudioItemEntity(
                id = audio.id,
                uriString = audio.uriString,
                label = audio.label,
                weight = audio.weight,
                volumePercent = audio.volumePercent,
                isActive = audio.isActive,
                audioType = audio.audioType.name,
                ttsText = audio.ttsText,
                dateAdded = audio.dateAdded
            )
        }
    }
}
