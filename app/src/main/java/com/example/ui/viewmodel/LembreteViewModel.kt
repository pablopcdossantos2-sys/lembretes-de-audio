package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.ProfileWithRestingPeriods
import com.example.data.db.ReminderEntity
import com.example.worker.ReminderWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class LembreteViewModel(application: Application) : AndroidViewModel(application) {

    // Todos os consumidores compartilham o mesmo singleton Room.
    private val database = AppDatabase.getDatabase(application)

    val profilesState: StateFlow<List<ProfileWithRestingPeriods>> =
        database.profileDao().getAllProfilesWithRestsFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    fun salvarLembreteGravado(
        titulo: String,
        arquivoAudio: File,
        periodicidadeMinutos: Int,
        selectedProfileId: Long? = null,
        hasRestPeriod: Boolean = false,
        horaInicio: Int = 0,
        minutoInicio: Int = 0,
        horaFim: Int = 23,
        minutoFim: Int = 59,
        onSucesso: () -> Unit,
        onErro: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            var persistentFile: File? = null

            // A cópia é validada antes de qualquer registro no banco.
            try {
                withContext(Dispatchers.IO) {
                    require(arquivoAudio.isFile && arquivoAudio.canRead()) {
                        "O arquivo temporário da gravação não existe ou não pode ser lido."
                    }

                    val extension = arquivoAudio.extension
                        .lowercase()
                        .ifBlank { "wav" }
                    val audioDir = File(app.filesDir, "audio_recordings").apply {
                        if (!exists() && !mkdirs()) {
                            throw IllegalStateException("Não foi possível criar a pasta de gravações.")
                        }
                    }
                    val destination = File(audioDir, "rec_${UUID.randomUUID()}.$extension")
                    persistentFile = destination

                    FileInputStream(arquivoAudio).use { input ->
                        FileOutputStream(destination).use { output ->
                            input.copyTo(output)
                            output.flush()
                            output.fd.sync()
                        }
                    }

                    val minimumLength = if (extension == "wav") 45L else 1L
                    require(destination.isFile && destination.canRead() && destination.length() >= minimumLength) {
                        "A gravação está vazia ou incompleta. Grave novamente antes de salvar."
                    }

                    val persistentUriString = android.net.Uri.fromFile(destination).toString()
                    val now = System.currentTimeMillis()

                    // Áudio, perfil e lembrete são persistidos como uma única operação.
                    database.withTransaction {
                        val audioEntity = AudioItemEntity(
                            uriString = persistentUriString,
                            label = titulo,
                            weight = 4,
                            volumePercent = 100,
                            isActive = true,
                            audioType = "AUDIO_FILE",
                            dateAdded = now
                        )
                        val audioId = database.audioDao().insertAudio(audioEntity)

                        val targetProfileId: Long =
                            if (selectedProfileId != null && selectedProfileId > 0L) {
                                val selected = database.profileDao()
                                    .getProfileWithRestsById(selectedProfileId)
                                    ?: throw IllegalStateException(
                                        "O perfil selecionado não existe mais. Selecione outro perfil."
                                    )

                                // CSV vazio significa todos os áudios; não o transforme em lista restrita.
                                val currentCsv = selected.profile.audioIdsCsv
                                if (currentCsv.isNotBlank()) {
                                    val currentIds = currentCsv.split(',')
                                        .mapNotNull { it.trim().toLongOrNull() }
                                    if (audioId !in currentIds) {
                                        val updatedCsv = (currentIds + audioId).joinToString(",")
                                        database.profileDao().updateProfile(
                                            selected.profile.copy(audioIdsCsv = updatedCsv)
                                        )
                                    }
                                }
                                selectedProfileId
                            } else {
                                val profile = ProfileEntity(
                                    name = titulo,
                                    isEnabled = true,
                                    priority = 20,
                                    daysOfWeekCsv = "1,2,3,4,5,6,7",
                                    startHour = 0,
                                    startMinute = 0,
                                    endHour = 23,
                                    endMinute = 59,
                                    intervalMinutes = periodicidadeMinutos.coerceIn(5, 120),
                                    playbackOrder = "SPECIFIC",
                                    specificAudioId = audioId,
                                    audioIdsCsv = audioId.toString(),
                                    resumePolicy = "NEXT_GRID_MARK",
                                    fadeInOverrideEnabled = false,
                                    fadeInDurationSeconds = 2.0f
                                )
                                val rests = if (
                                    hasRestPeriod &&
                                    (horaInicio != horaFim || minutoInicio != minutoFim)
                                ) {
                                    listOf(
                                        com.example.data.db.RestingPeriodEntity(
                                            label = "Descanso",
                                            startHour = horaInicio,
                                            startMinute = minutoInicio,
                                            endHour = horaFim,
                                            endMinute = minutoFim
                                        )
                                    )
                                } else {
                                    emptyList()
                                }
                                database.profileDao().upsertProfileWithRests(profile, rests)
                            }

                        val reminder = ReminderEntity(
                            profileId = targetProfileId,
                            title = titulo,
                            text = "Lembrete gravado a cada ${periodicidadeMinutos.coerceIn(5, 120)} minutos",
                            audioFilePath = destination.absolutePath,
                            audioUri = persistentUriString,
                            intervalMinutes = periodicidadeMinutos.coerceIn(5, 120),
                            isEnabled = true,
                            hasRestPeriod = hasRestPeriod,
                            startHour = if (hasRestPeriod) horaInicio else 0,
                            startMinute = if (hasRestPeriod) minutoInicio else 0,
                            endHour = if (hasRestPeriod) horaFim else 23,
                            endMinute = if (hasRestPeriod) minutoFim else 59,
                            dateCreated = now
                        )
                        database.reminderDao().insertReminder(reminder)
                    }

                    // O temporário só é removido depois do commit bem-sucedido.
                    val temporaryDir = File(app.filesDir, "lembretes_audio").canonicalFile
                    val source = arquivoAudio.canonicalFile
                    if (
                        source.parentFile == temporaryDir &&
                        source.absolutePath != destination.canonicalPath
                    ) {
                        source.delete()
                    }
                }
            } catch (error: Exception) {
                persistentFile?.delete()
                Log.e("LembreteViewModel", "Falha ao salvar gravação", error)
                withContext(Dispatchers.Main) {
                    onErro(error.message ?: "Não foi possível salvar a gravação.")
                }
                return@launch
            }

            // Uma falha ao atualizar o agendamento não desfaz um registro já confirmado no Room.
            withContext(Dispatchers.IO) {
                try {
                    val prefsRepo = com.example.data.preferences.UserPreferencesRepository(app)
                    prefsRepo.setAppMasterEnabled(true)
                    com.example.service.AudioReminderService.startOrRefresh(app)
                    ReminderWorkScheduler.rescheduleNextReminder(app)
                } catch (error: Exception) {
                    Log.e(
                        "LembreteViewModel",
                        "Gravação salva, mas houve falha ao atualizar o agendamento",
                        error
                    )
                }
            }

            withContext(Dispatchers.Main) {
                onSucesso()
            }
        }
    }
}
