package com.example.domain.model

import java.time.LocalTime

data class RestingPeriod(
    val id: Long = 0L,
    val label: String = "Descanso",
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    val crossesMidnight: Boolean
        get() = startTime.isAfter(endTime) || startTime == endTime

    fun contains(time: LocalTime): Boolean {
        return if (crossesMidnight) {
            !time.isBefore(startTime) || time.isBefore(endTime)
        } else {
            !time.isBefore(startTime) && time.isBefore(endTime)
        }
    }
}
