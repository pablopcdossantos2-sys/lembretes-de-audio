package com.example.domain.model

import java.time.ZonedDateTime

data class ScheduledTrigger(
    val triggerTime: ZonedDateTime,
    val profileId: Long,
    val profileName: String,
    val predictedAudioId: Long?,
    val predictedAudioLabel: String,
    val isRandomAudio: Boolean = false
)
