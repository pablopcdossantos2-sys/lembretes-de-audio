package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AudioReminderApp
import com.example.data.backup.BackupManager
import com.example.data.backup.ImportResult
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.ProfileWithRestingPeriods
import com.example.data.preferences.UserPreferences
import com.example.domain.model.AudioItem
import com.example.domain.model.Profile
import com.example.domain.model.ScheduledTrigger
import com.example.domain.scheduler.ScheduleCalculator
import com.example.player.AudioPlayerManager
import com.example.service.AudioReminderService
import com.example.util.DeviceStateHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class MainUiState(
    val preferences: UserPreferences = UserPreferences(),
    val profiles: List<Profile> = emptyList(),
    val audios: List<AudioItem> = emptyList(),
    val activeProfileToday: Profile? = null,
    val nextTriggers: List<ScheduledTrigger> = emptyList(),
    val isBatteryOptimizationIgnored: Boolean = true,
    val canScheduleExactAlarms: Boolean = true,
    val profileDayConflictWarning: String? = null,
    val previewingAudioId: Long? = null,
    val statusMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as? AudioReminderApp
    private val database = app?.database ?: com.example.data.db.AppDatabase.getDatabase(application)
    private val prefsRepo = app?.preferencesRepository ?: com.example.data.preferences.UserPreferencesRepository(application)
    private val backupManager = BackupManager(application, database, prefsRepo)
    private val previewPlayer = AudioPlayerManager(application)

    private val _previewingAudioId = MutableStateFlow<Long?>(null)
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage

    private val _deviceBatteryIgnored = MutableStateFlow(DeviceStateHelper.isIgnoringBatteryOptimizations(application))
    private val _deviceExactAlarm = MutableStateFlow(DeviceStateHelper.canScheduleExactAlarms(application))

    private val dbDataFlow = combine(
        prefsRepo.preferencesFlow,
        database.profileDao().getAllProfilesWithRestsFlow(),
        database.audioDao().getAllAudiosFlow()
    ) { prefs, profileEntities, audioEntities ->
        Triple(prefs, profileEntities, audioEntities)
    }

    val uiState: StateFlow<MainUiState> = combine(
        dbDataFlow,
        _previewingAudioId,
        _deviceBatteryIgnored,
        _deviceExactAlarm
    ) { (prefs, profileEntities, audioEntities), previewId, batteryIgnored, exactAlarm ->

        val domainAudios = audioEntities.map { entity ->
            val isMissing = if (entity.audioType == com.example.domain.model.AudioType.TEXT_TO_SPEECH.name) {
                entity.ttsText.isNullOrBlank()
            } else {
                try {
                    val uri = Uri.parse(entity.uriString)
                    if (uri.scheme == "file") {
                        val file = java.io.File(uri.path ?: "")
                        !file.exists() || !file.canRead()
                    } else {
                        val testStream = getApplication<Application>().contentResolver.openInputStream(uri)
                        val accessible = testStream != null
                        testStream?.close()
                        !accessible
                    }
                } catch (_: Exception) {
                    true
                }
            }
            entity.toDomain(isMissing = isMissing)
        }

        val domainProfiles = profileEntities.map { it.toDomain() }

        val now = ZonedDateTime.now()
        val nowMillis = System.currentTimeMillis()
        val activeAudios = domainAudios.filter { it.isActive && !it.isMissing }

        val snoozeUntil = if (prefs.snoozeUntilEpochMillis > nowMillis) {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(prefs.snoozeUntilEpochMillis), ZoneId.systemDefault())
        } else null

        val activeProfileToday = ScheduleCalculator.findActiveProfileFor(now, domainProfiles)

        val nextTriggers = if (prefs.isAppMasterEnabled) {
            ScheduleCalculator.getNextNTriggers(
                now = now,
                count = 5,
                profiles = domainProfiles,
                activeAudios = activeAudios,
                snoozeUntil = snoozeUntil,
                lastSequentialIndex = prefs.lastSequentialIndex
            )
        } else {
            emptyList()
        }

        val enabledProfiles = domainProfiles.filter { it.isEnabled }
        val dayCoverage = mutableMapOf<DayOfWeek, MutableList<String>>()
        for (p in enabledProfiles) {
            for (day in p.daysOfWeek) {
                dayCoverage.getOrPut(day) { mutableListOf() }.add(p.name)
            }
        }
        val conflictingDays = dayCoverage.filter { it.value.size > 1 }
        val conflictWarning = if (conflictingDays.isNotEmpty()) {
            val dayNames = conflictingDays.keys.joinToString(", ") { getDayOfWeekAbbreviation(it) }
            "Conflito de dias ($dayNames): prevalece o perfil de maior prioridade."
        } else null

        MainUiState(
            preferences = prefs,
            profiles = domainProfiles,
            audios = domainAudios,
            activeProfileToday = activeProfileToday,
            nextTriggers = nextTriggers,
            isBatteryOptimizationIgnored = batteryIgnored,
            canScheduleExactAlarms = exactAlarm,
            profileDayConflictWarning = conflictWarning,
            previewingAudioId = previewId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun refreshDeviceStatus() {
        _deviceBatteryIgnored.value = DeviceStateHelper.isIgnoringBatteryOptimizations(getApplication())
        _deviceExactAlarm.value = DeviceStateHelper.canScheduleExactAlarms(getApplication())
    }

    fun toggleAppMaster(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val activeAudios = database.audioDao().getActiveAudios()
                if (activeAudios.isEmpty()) {
                    _userMessage.emit("Adicione pelo menos um áudio ativo na biblioteca antes de ativar o app.")
                    return@launch
                }
                val enabledProfiles = database.profileDao().getAllProfilesWithRests().filter { it.profile.isEnabled }
                if (enabledProfiles.isEmpty()) {
                    _userMessage.emit("Ative ou crie pelo menos um perfil de funcionamento antes de ligar o app.")
                    return@launch
                }
                prefsRepo.setAppMasterEnabled(true)
                AudioReminderService.startOrRefresh(getApplication())
                com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            } else {
                prefsRepo.setAppMasterEnabled(false)
                AudioReminderService.stop(getApplication())
                com.example.worker.ReminderWorkScheduler.cancelAllWork(getApplication())
            }
        }
    }

    fun snooze(minutes: Int) {
        viewModelScope.launch {
            val snoozeUntil = System.currentTimeMillis() + (minutes * 60 * 1000L)
            prefsRepo.setSnoozeUntilEpochMillis(snoozeUntil)
            AudioReminderService.startOrRefresh(getApplication())
            _userMessage.emit("Silenciado por $minutes minutos.")
        }
    }

    fun resumeFromSnooze() {
        viewModelScope.launch {
            prefsRepo.setSnoozeUntilEpochMillis(0L)
            AudioReminderService.startOrRefresh(getApplication())
            _userMessage.emit("Lembretes retomados.")
        }
    }

    fun addAudios(uriStrings: List<Pair<String, String>>) {
        viewModelScope.launch {
            val entities = uriStrings.map { (uri, label) ->
                AudioItemEntity(
                    uriString = uri,
                    label = label,
                    weight = 3,
                    volumePercent = 100,
                    isActive = true
                )
            }
            database.audioDao().insertAudios(entities)
            _userMessage.emit("${entities.size} áudio(s) adicionado(s) à biblioteca.")
        }
    }

    fun addAudioItem(
        uriString: String,
        label: String,
        audioType: com.example.domain.model.AudioType = com.example.domain.model.AudioType.AUDIO_FILE,
        ttsText: String? = null
    ) {
        viewModelScope.launch {
            val entity = AudioItemEntity(
                uriString = uriString,
                label = label,
                weight = 3,
                volumePercent = 100,
                isActive = true,
                audioType = audioType.name,
                ttsText = ttsText
            )
            database.audioDao().insertAudio(entity)
            _userMessage.emit("Lembrete \"$label\" adicionado com sucesso.")
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
            }
        }
    }

    fun updateAudio(audio: AudioItem) {
        viewModelScope.launch {
            database.audioDao().updateAudio(AudioItemEntity.fromDomain(audio))
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
            }
        }
    }

    fun deleteAudio(audioId: Long) {
        viewModelScope.launch {
            stopPreview()
            database.audioDao().deleteAudioById(audioId)
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
            }
        }
    }

    fun previewAudio(audio: AudioItem) {
        if (_previewingAudioId.value == audio.id) {
            stopPreview()
            return
        }
        stopPreview()
        _previewingAudioId.value = audio.id
        val volume = (uiState.value.preferences.masterVolumePercent * audio.volumePercent) / 10000f
        previewPlayer.playAudio(
            uriString = audio.uriString,
            targetVolume = volume,
            fadeInDurationMs = 0L,
            onComplete = {
                _previewingAudioId.value = null
            }
        )
    }

    fun stopPreview() {
        previewPlayer.stop()
        _previewingAudioId.value = null
    }

    fun saveProfile(profile: Profile) {
        viewModelScope.launch {
            val (entity, rests) = ProfileWithRestingPeriods.fromDomain(profile)
            database.profileDao().upsertProfileWithRests(entity, rests)
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
                com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            }
            _userMessage.emit("Perfil \"${profile.name}\" salvo.")
        }
    }

    fun duplicateProfile(profile: Profile) {
        viewModelScope.launch {
            val duplicated = profile.copy(
                id = 0L,
                name = "${profile.name} (Cópia)",
                restingPeriods = profile.restingPeriods.map { it.copy(id = 0L) }
            )
            saveProfile(duplicated)
        }
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch {
            database.profileDao().deleteProfileById(profileId)
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
                com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            }
            _userMessage.emit("Perfil excluído.")
        }
    }

    fun toggleProfileEnabled(profileId: Long, enabled: Boolean) {
        viewModelScope.launch {
            database.profileDao().setProfileEnabled(profileId, enabled)
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
                com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            }
        }
    }

    fun updatePreferences(
        masterVolume: Int? = null,
        useAppVolumeOnly: Boolean? = null,
        fadeInEnabled: Boolean? = null,
        fadeInDuration: Float? = null,
        vibrate: Boolean? = null,
        showLabelInNotif: Boolean? = null,
        respectDnd: Boolean? = null,
        defaultResumePolicy: String? = null
    ) {
        viewModelScope.launch {
            masterVolume?.let { prefsRepo.setMasterVolumePercent(it) }
            useAppVolumeOnly?.let { prefsRepo.setUseAppVolumeOnly(it) }
            fadeInEnabled?.let { prefsRepo.setFadeInEnabled(it) }
            fadeInDuration?.let { prefsRepo.setFadeInDurationSeconds(it) }
            vibrate?.let { prefsRepo.setVibrateWithAudio(it) }
            showLabelInNotif?.let { prefsRepo.setShowLabelInNotification(it) }
            respectDnd?.let { prefsRepo.setRespectDoNotDisturb(it) }
            defaultResumePolicy?.let { prefsRepo.setDefaultResumePolicy(it) }

            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
                com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            }
        }
    }

    fun updateQuietHours(
        enabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysCsv: String,
        notifySilently: Boolean = true
    ) {
        viewModelScope.launch {
            prefsRepo.setQuietHours(
                enabled = enabled,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                daysCsv = daysCsv,
                notifySilently = notifySilently
            )
            // Atualiza e sincroniza agendamento do WorkManager
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
            }
            _userMessage.emit("Horário de descanso salvo e integrado ao WorkManager.")
        }
    }

    fun toggleQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setQuietHoursEnabled(enabled)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            if (uiState.value.preferences.isAppMasterEnabled) {
                AudioReminderService.startOrRefresh(getApplication())
            }
            val status = if (enabled) "ativado" else "desativado"
            _userMessage.emit("Horário de Não Perturbe $status.")
        }
    }

    suspend fun exportBackup(outputStream: OutputStream): Boolean {
        return backupManager.exportConfig(outputStream)
    }

    suspend fun importBackup(inputStream: InputStream): ImportResult {
        val result = backupManager.importConfig(inputStream)
        if (result.success && uiState.value.preferences.isAppMasterEnabled) {
            AudioReminderService.startOrRefresh(getApplication())
        }
        return result
    }

    override fun onCleared() {
        previewPlayer.stop()
        super.onCleared()
    }

    companion object {
        fun getDayOfWeekAbbreviation(day: DayOfWeek): String {
            return when (day) {
                DayOfWeek.SUNDAY -> "Dom"
                DayOfWeek.MONDAY -> "Seg"
                DayOfWeek.TUESDAY -> "Ter"
                DayOfWeek.WEDNESDAY -> "Qua"
                DayOfWeek.THURSDAY -> "Qui"
                DayOfWeek.FRIDAY -> "Sex"
                DayOfWeek.SATURDAY -> "Sáb"
            }
        }
    }
}
