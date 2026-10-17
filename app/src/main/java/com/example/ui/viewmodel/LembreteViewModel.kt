package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class LembreteViewModel(application: Application) : AndroidViewModel(application) {

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
        onSucesso: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val audioDir = File(app.filesDir, "audio_recordings").apply { mkdirs() }
                val extension = arquivoAudio.extension.ifBlank { "wav" }
                val persistentFile = File(audioDir, "rec_${System.currentTimeMillis()}.$extension")

                // Copia o arquivo temporário para o armazenamento interno permanente do app
                if (arquivoAudio.exists() && arquivoAudio.absolutePath != persistentFile.absolutePath) {
                    FileInputStream(arquivoAudio).use { input ->
                        FileOutputStream(persistentFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val persistentUriString = android.net.Uri.fromFile(persistentFile).toString()

                // Salva na biblioteca de áudios (AudioDao)
                val audioEntity = AudioItemEntity(
                    uriString = persistentUriString,
                    label = titulo,
                    weight = 4,
                    volumePercent = 100,
                    isActive = true,
                    audioType = "AUDIO_FILE",
                    dateAdded = System.currentTimeMillis()
                )
                val audioId = database.audioDao().insertAudio(audioEntity)

                // Define ou localiza o perfil de usuário associado
                val targetProfileId: Long = if (selectedProfileId != null && selectedProfileId > 0) {
                    selectedProfileId
                } else {
                    // Cria ou atualiza perfil correspondente
                    val profileEntity = ProfileEntity(
                        name = titulo,
                        isEnabled = true,
                        priority = 20, // Prioridade alta
                        daysOfWeekCsv = "1,2,3,4,5,6,7",
                        startHour = if (hasRestPeriod) horaInicio else 0,
                        startMinute = if (hasRestPeriod) minutoInicio else 0,
                        endHour = if (hasRestPeriod) horaFim else 23,
                        endMinute = if (hasRestPeriod) minutoFim else 59,
                        intervalMinutes = periodicidadeMinutos,
                        playbackOrder = "SPECIFIC",
                        specificAudioId = audioId,
                        audioIdsCsv = audioId.toString(),
                        resumePolicy = "NEXT_GRID_MARK",
                        fadeInOverrideEnabled = false,
                        fadeInDurationSeconds = 2.0f
                    )
                    database.profileDao().upsertProfileWithRests(profileEntity, emptyList())
                }

                // Salva o lembrete no Room com caminho do arquivo gravado, título, periodicidade e vínculo ao perfil
                val reminderEntity = ReminderEntity(
                    profileId = targetProfileId,
                    title = titulo,
                    text = "Lembrete gravado a cada $periodicidadeMinutos minutos",
                    audioFilePath = persistentFile.absolutePath,
                    audioUri = persistentUriString,
                    intervalMinutes = periodicidadeMinutos,
                    isEnabled = true,
                    hasRestPeriod = hasRestPeriod,
                    startHour = if (hasRestPeriod) horaInicio else 0,
                    startMinute = if (hasRestPeriod) minutoInicio else 0,
                    endHour = if (hasRestPeriod) horaFim else 23,
                    endMinute = if (hasRestPeriod) minutoFim else 59,
                    dateCreated = System.currentTimeMillis()
                )
                database.reminderDao().insertReminder(reminderEntity)

                // Garante que o aplicativo esteja com o botão mestre ativado e o serviço agendando
                val prefsRepo = com.example.data.preferences.UserPreferencesRepository(app)
                prefsRepo.setAppMasterEnabled(true)

                // Atualiza o agendamento no serviço em primeiro plano e no WorkManager
                com.example.service.AudioReminderService.startOrRefresh(app)
                ReminderWorkScheduler.rescheduleNextReminder(app)

                withContext(Dispatchers.Main) {
                    onSucesso()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
