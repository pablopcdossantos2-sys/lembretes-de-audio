package com.example.domain.model

data class AudioItem(
    val id: Long = 0L,
    val uriString: String,
    val label: String,
    val weight: Int = 3,
    val volumePercent: Int = 100,
    val isActive: Boolean = true,
    val isMissing: Boolean = false,
    val audioType: AudioType = AudioType.AUDIO_FILE,
    val ttsText: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
)
