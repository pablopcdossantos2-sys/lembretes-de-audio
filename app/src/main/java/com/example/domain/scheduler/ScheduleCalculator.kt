package com.example.domain.scheduler

import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import com.example.domain.model.ScheduledTrigger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure calculation logic for audio reminder scheduling.
 * Independent of Android SDK, fully unit testable with java.time.
 */
object ScheduleCalculator {

    /**
     * Checks if a given time falls within any resting period in the list.
     * Rule: Exactly at startTime is RESTING (does not play).
     * Exactly at endTime is FINISHED RESTING (allowed to play).
     */
    fun isTimeInRestingPeriod(time: LocalTime, restingPeriods: List<RestingPeriod>): Boolean {
        return restingPeriods.any { it.contains(time) }
    }

    /**
     * Finds the rest period that covers the given time, if any.
     */
    fun findCoveringRest(time: LocalTime, restingPeriods: List<RestingPeriod>): RestingPeriod? {
        return restingPeriods.firstOrNull { it.contains(time) }
    }

    /**
     * Checks if a given time on a specific day of week falls within the scheduled Quiet Hours / Não Perturbe.
     * Takes into account active toggle, configured days of week, and periods crossing midnight (e.g. 22:00 to 07:00).
     */
    fun isTimeInQuietHours(
        time: LocalTime,
        dayOfWeek: java.time.DayOfWeek,
        enabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeekCsv: String
    ): Boolean {
        if (!enabled) return false
        val selectedDays = parseDaysOfWeek(daysOfWeekCsv)
        if (!selectedDays.contains(dayOfWeek)) return false

        val period = RestingPeriod(
            label = "Horário de Descanso / Não Perturbe",
            startTime = LocalTime.of(startHour.coerceIn(0, 23), startMinute.coerceIn(0, 59)),
            endTime = LocalTime.of(endHour.coerceIn(0, 23), endMinute.coerceIn(0, 59))
        )
        return period.contains(time)
    }

    /**
     * Parses a comma-separated list of day of week numbers (1=Monday..7=Sunday).
     */
    fun parseDaysOfWeek(csv: String): Set<java.time.DayOfWeek> {
        if (csv.isBlank()) return java.time.DayOfWeek.values().toSet()
        return csv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { dayInt ->
                try {
                    java.time.DayOfWeek.of(dayInt)
                } catch (e: Exception) {
                    null
                }
            }
            .toSet()
    }

    /**
     * Merges touching or overlapping resting periods into a clean, minimal list.
     * Handles both same-day rests and rests that cross midnight.
     */
    fun mergeRestingPeriods(periods: List<RestingPeriod>): List<RestingPeriod> {
        if (periods.isEmpty()) return emptyList()

        // Represent each minute of the day [0..1439] with a boolean flag
        val minutesCovered = BooleanArray(1440) { false }

        for (p in periods) {
            val startMin = p.startTime.hour * 60 + p.startTime.minute
            val endMin = p.endTime.hour * 60 + p.endTime.minute

            if (startMin == endMin && p.label != "Descanso total") {
                continue
            }

            if (p.crossesMidnight) {
                // From startMin to 1439
                for (m in startMin until 1440) minutesCovered[m] = true
                // From 0 to endMin - 1 (endMin is exclusive)
                for (m in 0 until endMin) minutesCovered[m] = true
            } else {
                for (m in startMin until endMin) minutesCovered[m] = true
            }
        }

        // If entire day is covered
        if (minutesCovered.all { it }) {
            return listOf(
                RestingPeriod(
                    label = "Descanso total",
                    startTime = LocalTime.of(0, 0),
                    endTime = LocalTime.of(0, 0)
                )
            )
        }

        // Extract contiguous intervals
        val result = mutableListOf<RestingPeriod>()
        // If minute 0 and 1439 are both covered, an interval crosses midnight.
        // We find the first uncovered minute to start our scan cleanly.
        val firstUncovered = minutesCovered.indexOfFirst { !it }
        if (firstUncovered == -1) {
            return listOf(
                RestingPeriod(
                    label = "Descanso total",
                    startTime = LocalTime.of(0, 0),
                    endTime = LocalTime.of(0, 0)
                )
            )
        }

        var i = 0
        while (i < 1440) {
            val actualMin = (firstUncovered + i) % 1440
            if (minutesCovered[actualMin]) {
                val intervalStart = actualMin
                var len = 0
                while (len < 1440 && minutesCovered[(firstUncovered + i + len) % 1440]) {
                    len++
                }
                val intervalEnd = (intervalStart + len) % 1440
                val startH = intervalStart / 60
                val startM = intervalStart % 60
                val endH = intervalEnd / 60
                val endM = intervalEnd % 60

                // Label matching from original periods if possible
                val label = periods.firstOrNull {
                    val sM = it.startTime.hour * 60 + it.startTime.minute
                    sM == intervalStart
                }?.label ?: "Descanso"

                result.add(
                    RestingPeriod(
                        label = label,
                        startTime = LocalTime.of(startH, startM),
                        endTime = LocalTime.of(endH, endM)
                    )
                )
                i += len
            } else {
                i++
            }
        }

        return result
    }

    /**
     * Checks if rests cover the entirety of operating hours.
     * If true, the profile will never produce any triggers.
     */
    fun doRestsCoverEntireOperatingHours(profile: Profile): Boolean {
        val mergedRests = mergeRestingPeriods(profile.restingPeriods)
        val startMin = profile.startTime.hour * 60 + profile.startTime.minute
        val endMin = profile.endTime.hour * 60 + profile.endTime.minute

        val totalOperatingMinutes = if (profile.crossesMidnight) {
            (1440 - startMin) + endMin
        } else {
            endMin - startMin
        }

        if (totalOperatingMinutes <= 0) return true

        var uncoveredCount = 0
        for (step in 0..totalOperatingMinutes) {
            val currentMin = (startMin + step) % 1440
            val t = LocalTime.of(currentMin / 60, currentMin % 60)
            if (!isTimeInRestingPeriod(t, mergedRests)) {
                uncoveredCount++
            }
        }

        return uncoveredCount == 0
    }

    /**
     * Resolves the active profile for a given date/time from a list of profiles.
     * Takes into account enabled status, day of week, operating hours, and priority.
     */
    fun findActiveProfileFor(
        dateTime: ZonedDateTime,
        profiles: List<Profile>
    ): Profile? {
        val activeProfiles = profiles.filter { it.isEnabled }
        if (activeProfiles.isEmpty()) return null

        // Candidate profiles that cover today's day of week or yesterday's overnight profile
        val candidates = activeProfiles.filter { profile ->
            isProfileActiveAt(profile, dateTime)
        }

        // Return highest priority profile
        return candidates.maxWithOrNull(
            compareBy<Profile> { it.priority }.thenByDescending { it.id }
        )
    }

    /**
     * Checks if a profile is currently active at the given dateTime,
     * including overnight profiles from the previous day.
     */
    fun isProfileActiveAt(profile: Profile, dateTime: ZonedDateTime): Boolean {
        if (!profile.isEnabled) return false

        val dayOfWeek = dateTime.dayOfWeek
        val time = dateTime.toLocalTime()

        if (profile.crossesMidnight) {
            // Can be active either:
            // 1. Started today on or after startTime (today is in profile.daysOfWeek)
            if (profile.daysOfWeek.contains(dayOfWeek) && !time.isBefore(profile.startTime)) {
                return true
            }
            // 2. Started yesterday before midnight and ends today on or before endTime (yesterday was in profile.daysOfWeek)
            val yesterday = dayOfWeek.minus(1)
            if (profile.daysOfWeek.contains(yesterday) && !time.isAfter(profile.endTime)) {
                return true
            }
            return false
        } else {
            return profile.daysOfWeek.contains(dayOfWeek) && profile.isWithinOperatingHours(time)
        }
    }

    /**
     * Computes the next valid trigger strictly after [now].
     */
    fun getNextTrigger(
        now: ZonedDateTime,
        profiles: List<Profile>,
        activeAudios: List<AudioItem>,
        snoozeUntil: ZonedDateTime? = null,
        lastSequentialIndex: Int = 0
    ): ScheduledTrigger? {
        val triggers = getNextNTriggers(
            now = now,
            count = 1,
            profiles = profiles,
            activeAudios = activeAudios,
            snoozeUntil = snoozeUntil,
            lastSequentialIndex = lastSequentialIndex
        )
        return triggers.firstOrNull()
    }

    /**
     * Computes the next [count] valid triggers from [now].
     * Scans day-by-day up to 14 days ahead.
     */
    fun getNextNTriggers(
        now: ZonedDateTime,
        count: Int,
        profiles: List<Profile>,
        activeAudios: List<AudioItem>,
        snoozeUntil: ZonedDateTime? = null,
        lastSequentialIndex: Int = 0
    ): List<ScheduledTrigger> {
        val enabledProfiles = profiles.filter { it.isEnabled }
        if (enabledProfiles.isEmpty() || activeAudios.isEmpty()) {
            return emptyList()
        }

        val triggers = mutableListOf<ScheduledTrigger>()
        var searchDate = now.toLocalDate()
        val zone = now.zone
        val maxDaysToSearch = 14
        var daysSearched = 0
        var currentSeqIndex = lastSequentialIndex

        // Threshold time: must be strictly after now, and if snoozed, after snoozeUntil
        val effectiveStart = if (snoozeUntil != null && snoozeUntil.isAfter(now)) {
            snoozeUntil
        } else {
            now
        }

        while (triggers.size < count && daysSearched < maxDaysToSearch) {
            val candidatesForDay = mutableListOf<ScheduledTrigger>()

            // Encontra todos os disparos candidatos de TODOS os perfis habilitados neste dia
            for (profile in enabledProfiles) {
                if (!profile.daysOfWeek.contains(searchDate.dayOfWeek)) {
                    continue
                }

                val mergedRests = mergeRestingPeriods(profile.restingPeriods)
                val startDateTime = ZonedDateTime.of(searchDate, profile.startTime, zone)
                val endDateTime = if (profile.crossesMidnight) {
                    ZonedDateTime.of(searchDate.plusDays(1), profile.endTime, zone)
                } else {
                    ZonedDateTime.of(searchDate, profile.endTime, zone)
                }

                var currentInstant = startDateTime
                val stepMinutes = profile.intervalMinutes.toLong().coerceAtLeast(1L)

                while (!currentInstant.isAfter(endDateTime)) {
                    // Checa se o horário do gatilho é estritamente no futuro em relação ao momento de corte
                    if (currentInstant.isAfter(effectiveStart)) {
                        val time = currentInstant.toLocalTime()
                        val coveringRest = findCoveringRest(time, mergedRests)

                        if (coveringRest == null) {
                            val (audioId, audioLabel, isRandom) = selectAudioForProfile(
                                profile = profile,
                                activeAudios = activeAudios,
                                seqIndex = currentSeqIndex
                            )
                            candidatesForDay.add(
                                ScheduledTrigger(
                                    triggerTime = currentInstant,
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    predictedAudioId = audioId,
                                    predictedAudioLabel = audioLabel,
                                    isRandomAudio = isRandom
                                )
                            )
                        } else if (profile.resumePolicy == ResumePolicy.AT_REST_END) {
                            val restEndInstant = if (coveringRest.crossesMidnight) {
                                if (time.isBefore(coveringRest.endTime)) {
                                    ZonedDateTime.of(currentInstant.toLocalDate(), coveringRest.endTime, zone)
                                } else {
                                    ZonedDateTime.of(currentInstant.toLocalDate().plusDays(1), coveringRest.endTime, zone)
                                }
                            } else {
                                ZonedDateTime.of(currentInstant.toLocalDate(), coveringRest.endTime, zone)
                            }

                            if (restEndInstant.isAfter(effectiveStart) &&
                                !restEndInstant.isAfter(endDateTime) &&
                                !isTimeInRestingPeriod(restEndInstant.toLocalTime(), mergedRests)
                            ) {
                                val (audioId, audioLabel, isRandom) = selectAudioForProfile(
                                    profile = profile,
                                    activeAudios = activeAudios,
                                    seqIndex = currentSeqIndex
                                )
                                candidatesForDay.add(
                                    ScheduledTrigger(
                                        triggerTime = restEndInstant,
                                        profileId = profile.id,
                                        profileName = profile.name,
                                        predictedAudioId = audioId,
                                        predictedAudioLabel = audioLabel,
                                        isRandomAudio = isRandom
                                    )
                                )
                            }
                        }
                    }

                    currentInstant = currentInstant.plusMinutes(stepMinutes)
                }
            }

            // Desempate de mesmo horário: mantém o gatilho com maior prioridade de perfil
            val deduplicated = candidatesForDay
                .groupBy { it.triggerTime }
                .map { (_, list) ->
                    list.maxWithOrNull(
                        compareBy<ScheduledTrigger> { trigger ->
                            enabledProfiles.firstOrNull { it.id == trigger.profileId }?.priority ?: 0
                        }.thenByDescending { it.profileId }
                    )!!
                }
                .sortedBy { it.triggerTime }

            for (trig in deduplicated) {
                if (triggers.size < count) {
                    triggers.add(trig)
                    currentSeqIndex++
                }
            }

            searchDate = searchDate.plusDays(1)
            daysSearched++
        }

        // Ordena cronologicamente e garante o retorno dos primeiros solicitados
        return triggers.sortedBy { it.triggerTime }.take(count)
    }

    /**
     * Determines which audio will play (or label) based on profile order rules.
     */
    private fun selectAudioForProfile(
        profile: Profile,
        activeAudios: List<AudioItem>,
        seqIndex: Int
    ): Triple<Long?, String, Boolean> {
        val eligibleAudios = if (profile.audioIds.isNotEmpty()) {
            activeAudios.filter { profile.audioIds.contains(it.id) }
        } else {
            activeAudios
        }

        if (eligibleAudios.isEmpty()) {
            return Triple(null, "Nenhum áudio disponível", false)
        }

        return when (profile.playbackOrder) {
            PlaybackOrder.SPECIFIC -> {
                val specific = eligibleAudios.firstOrNull { it.id == profile.specificAudioId }
                    ?: eligibleAudios.first()
                Triple(specific.id, specific.label, false)
            }
            PlaybackOrder.RANDOM -> {
                Triple(null, "Áudio aleatório", true)
            }
            PlaybackOrder.SEQUENTIAL -> {
                val index = (seqIndex % eligibleAudios.size + eligibleAudios.size) % eligibleAudios.size
                val item = eligibleAudios[index]
                Triple(item.id, item.label, false)
            }
        }
    }

    /**
     * Chooses the actual random audio using weights (1..5).
     */
    fun pickWeightedRandomAudio(audios: List<AudioItem>): AudioItem? {
        val active = audios.filter { it.isActive && !it.isMissing }
        if (active.isEmpty()) return null

        val totalWeight = active.sumOf { it.weight.coerceIn(1, 5) }
        val randomValue = (1..totalWeight).random()
        var accumulated = 0

        for (audio in active) {
            accumulated += audio.weight.coerceIn(1, 5)
            if (randomValue <= accumulated) {
                return audio
            }
        }
        return active.first()
    }
}
