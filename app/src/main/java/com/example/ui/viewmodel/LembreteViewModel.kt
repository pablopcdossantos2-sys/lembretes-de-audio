package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ReminderEntity
import com.example.worker.ReminderWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class LembreteViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)

    fun salvarLembreteGravado(
        titulo: String,
        arquivoAudio: File,
        periodicidadeMinutos: Int,
        horaInicio: Int,
        minutoInicio: Int,
        horaFim: Int,
        minutoFim: Int,
        onSucesso: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val audioDir = File(app.filesDir, "audio_recordings").apply { mkdirs() }
                val persistentFile = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")

                // Copia o arquivo temporário para o armazenamento interno permanente do app
                if (arquivoAudio.exists() && arquivoAudio.absolutePath != persistentFile.absolutePath) {
                    FileInputStream(arquivoAudio).use { input ->
                        FileOutputStream(persistentFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val persistentUriString = persistentFile.toURI().toString()

                // Salva na biblioteca de áudios (AudioDao)
                val audioEntity = AudioItemEntity(
                    uriString = persistentUriString,
                    label = titulo,
                    weight = 3,
                    volumePercent = 100,
                    isActive = true,
                    audioType = "AUDIO_FILE",
                    dateAdded = System.currentTimeMillis()
                )
                val audioId = database.audioDao().insertAudio(audioEntity)

                // Salva o lembrete com a janela de horário e descanso configurados (ReminderDao)
                val reminderEntity = ReminderEntity(
                    title = titulo,
                    text = "Lembrete gravado a cada $periodicidadeMinutos minutos",
                    audioUri = persistentUriString,
                    intervalMinutes = periodicidadeMinutos,
                    isEnabled = true,
                    startHour = horaInicio,
                    startMinute = minutoInicio,
                    endHour = horaFim,
                    endMinute = minutoFim,
                    dateCreated = System.currentTimeMillis()
                )
                database.reminderDao().insertReminder(reminderEntity)

                // Atualiza o agendamento em background
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
