package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.example.AudioReminderApp
import com.example.data.db.LembreteDao
import com.example.data.db.LembreteEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel responsável pela interação com o LembreteDao do Room
 * e controle de reprodução dos áudios gravados diretamente na tela de listagem.
 */
class LembreteViewModel(
    application: Application,
    private val lembreteDao: LembreteDao = (application as? AudioReminderApp)?.database?.reminderDao()
        ?: com.example.data.db.AppDatabase.getDatabase(application).reminderDao()
) : AndroidViewModel(application) {

    // Fluxo reativo para listar todos os lembretes cadastrados
    val lembretes: StateFlow<List<LembreteEntity>> = lembreteDao.getAllRemindersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Fluxo reativo apenas para os lembretes ativos
    val lembretesAtivos: StateFlow<List<LembreteEntity>> = lembreteDao.getActiveRemindersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Estado do reprodutor de áudio integrado para a listagem
    private val _currentlyPlayingReminderId = MutableStateFlow<Long?>(null)
    val currentlyPlayingReminderId: StateFlow<Long?> = _currentlyPlayingReminderId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackerJob: Job? = null

    // Mensagens de feedback para a interface
    private val _mensagem = MutableSharedFlow<String>()
    val mensagem: SharedFlow<String> = _mensagem

    /**
     * Adiciona um novo lembrete à base de dados local.
     */
    fun adicionarLembrete(lembrete: LembreteEntity) {
        viewModelScope.launch {
            lembreteDao.insertReminder(lembrete)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            _mensagem.emit("Lembrete \"${lembrete.title}\" adicionado com sucesso.")
        }
    }

    /**
     * Sobrecarga conveniente para criar e adicionar um lembrete com parâmetros diretos.
     */
    fun adicionarLembrete(
        titulo: String,
        texto: String? = null,
        audioUri: String? = null,
        periodicidadeMinutos: Int = 15,
        diasSemanaCsv: String = "1,2,3,4,5,6,7",
        horaInicio: Int = 8,
        minutoInicio: Int = 0,
        horaFim: Int = 22,
        minutoFim: Int = 0,
        tipoAudio: String = "AUDIO_FILE",
        volumePercentual: Int = 100
    ) {
        val novoLembrete = LembreteEntity(
            title = titulo,
            text = texto,
            audioUri = audioUri,
            periodicityMinutes = periodicidadeMinutos,
            daysOfWeekCsv = diasSemanaCsv,
            startHour = horaInicio,
            startMinute = minutoInicio,
            endHour = horaFim,
            endMinute = minutoFim,
            isEnabled = true,
            audioType = tipoAudio,
            volumePercent = volumePercentual
        )
        adicionarLembrete(novoLembrete)
    }

    /**
     * Atualiza um lembrete existente.
     */
    fun atualizarLembrete(lembrete: LembreteEntity) {
        viewModelScope.launch {
            lembreteDao.updateReminder(lembrete)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            _mensagem.emit("Lembrete \"${lembrete.title}\" atualizado.")
        }
    }

    /**
     * Deleta um lembrete da base de dados local.
     */
    fun deletarLembrete(lembrete: LembreteEntity) {
        viewModelScope.launch {
            lembreteDao.deleteReminder(lembrete)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            _mensagem.emit("Lembrete \"${lembrete.title}\" excluído.")
        }
    }

    /**
     * Deleta um lembrete pelo identificador (ID).
     */
    fun deletarLembretePorId(id: Long) {
        viewModelScope.launch {
            lembreteDao.deleteReminderById(id)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            _mensagem.emit("Lembrete excluído com sucesso.")
        }
    }

    /**
     * Ativa ou desativa um lembrete sem apagá-lo.
     */
    fun alternarStatusLembrete(id: Long, ativo: Boolean) {
        viewModelScope.launch {
            lembreteDao.setReminderEnabled(id, ativo)
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
        }
    }

    /**
     * Remove todos os lembretes da base de dados local.
     */
    fun limparTodosLembretes() {
        viewModelScope.launch {
            lembreteDao.clearAllReminders()
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())
            _mensagem.emit("Todos os lembretes foram removidos.")
        }
    }

    /**
     * Dispara a execução imediata do WorkManager para testar o background worker.
     */
    fun testarWorkManagerImediato() {
        com.example.worker.ReminderWorkScheduler.enqueueImmediateTest(getApplication())
        viewModelScope.launch {
            _mensagem.emit("Tarefa do WorkManager disparada em segundo plano.")
        }
    }

    /**
     * Salva o caminho do arquivo de áudio capturado via MediaRecorder API
     * e os parâmetros do lembrete (título, periodicidade, horários) no Room.
     */
    fun salvarLembreteGravado(
        titulo: String,
        arquivoAudio: java.io.File,
        periodicidadeMinutos: Int = 15,
        diasSemanaCsv: String = "1,2,3,4,5,6,7",
        horaInicio: Int = 8,
        minutoInicio: Int = 0,
        horaFim: Int = 22,
        minutoFim: Int = 0,
        onSucesso: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val audioUriString = android.net.Uri.fromFile(arquivoAudio).toString()
            val novoLembrete = LembreteEntity(
                title = titulo.ifBlank { "Lembrete Gravado" },
                text = "Áudio capturado via MediaRecorder",
                audioUri = audioUriString,
                periodicityMinutes = periodicidadeMinutos,
                daysOfWeekCsv = diasSemanaCsv,
                startHour = horaInicio,
                startMinute = minutoInicio,
                endHour = horaFim,
                endMinute = minutoFim,
                isEnabled = true,
                audioType = "RECORDING",
                volumePercent = 100
            )

            val id = lembreteDao.insertReminder(novoLembrete)

            // Salva também na AudioDao para reprodução periódica pelo serviço
            val audioDao = (getApplication() as? AudioReminderApp)?.database?.audioDao()
            audioDao?.insertAudio(
                com.example.data.db.AudioItemEntity(
                    uriString = audioUriString,
                    label = titulo.ifBlank { "Lembrete Gravado" },
                    weight = 3,
                    volumePercent = 100,
                    isActive = true,
                    audioType = "RECORDING"
                )
            )

            // Agenda a execução periódica no WorkManager
            com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(getApplication())

            _mensagem.emit("Áudio gravado e agendado no WorkManager com sucesso!")
            onSucesso?.invoke(id)
        }
    }

    /**
     * Alterna a reprodução do áudio de um lembrete (Play / Pause / Troca de faixa).
     */
    fun alternarReproducao(lembrete: LembreteEntity) {
        if (_currentlyPlayingReminderId.value == lembrete.id) {
            if (_isPlaying.value) {
                pausarAudio()
            } else {
                retomarAudio()
            }
        } else {
            tocarAudioLembrete(lembrete)
        }
    }

    /**
     * Inicia a reprodução do áudio gravado no lembrete com rastreamento de progresso.
     */
    fun tocarAudioLembrete(lembrete: LembreteEntity) {
        val uriStr = lembrete.audioUri
        if (uriStr.isNullOrBlank()) {
            viewModelScope.launch {
                _mensagem.emit("Este lembrete não possui áudio gravado.")
            }
            return
        }

        try {
            pararAudio()

            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }

            val uri = Uri.parse(uriStr)
            mp.setDataSource(getApplication(), uri)

            val volume = (lembrete.volumePercent / 100f).coerceIn(0.05f, 1.0f)
            mp.setVolume(volume, volume)

            mp.setOnPreparedListener { preparedMp ->
                _durationMs.value = preparedMp.duration
                preparedMp.start()
                _isPlaying.value = true
                _currentlyPlayingReminderId.value = lembrete.id
                iniciarRastreadorProgresso()
            }

            mp.setOnCompletionListener {
                _isPlaying.value = false
                _currentPositionMs.value = 0
                _currentlyPlayingReminderId.value = null
                pararRastreadorProgresso()
            }

            mp.setOnErrorListener { _, _, _ ->
                _isPlaying.value = false
                _currentlyPlayingReminderId.value = null
                pararRastreadorProgresso()
                viewModelScope.launch {
                    _mensagem.emit("Erro ao tentar reproduzir arquivo de áudio.")
                }
                true
            }

            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            _isPlaying.value = false
            _currentlyPlayingReminderId.value = null
            pararRastreadorProgresso()
            viewModelScope.launch {
                _mensagem.emit("Não foi possível acessar a gravação: ${e.message ?: "Arquivo indisponível"}")
            }
        }
    }

    /**
     * Pausa a reprodução do áudio em andamento.
     */
    fun pausarAudio() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    _isPlaying.value = false
                    pararRastreadorProgresso()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Retoma a reprodução pausada.
     */
    fun retomarAudio() {
        try {
            mediaPlayer?.let { mp ->
                mp.start()
                _isPlaying.value = true
                iniciarRastreadorProgresso()
            }
        } catch (_: Exception) {}
    }

    /**
     * Para a reprodução atual e zera a posição.
     */
    fun pararAudio() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.reset()
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _currentlyPlayingReminderId.value = null
        _currentPositionMs.value = 0
        _durationMs.value = 0
        pararRastreadorProgresso()
    }

    /**
     * Busca uma posição específica em milissegundos no áudio.
     */
    fun buscarPosicao(posicaoMs: Int) {
        try {
            mediaPlayer?.let { mp ->
                val destino = posicaoMs.coerceIn(0, _durationMs.value.coerceAtLeast(0))
                mp.seekTo(destino)
                _currentPositionMs.value = destino
            }
        } catch (_: Exception) {}
    }

    /**
     * Pula a reprodução em segundos (ex: -5s ou +5s).
     */
    fun pularTempo(segundos: Int) {
        val novaPosicao = _currentPositionMs.value + (segundos * 1000)
        buscarPosicao(novaPosicao)
    }

    private fun iniciarRastreadorProgresso() {
        progressTrackerJob?.cancel()
        progressTrackerJob = viewModelScope.launch {
            while (_isPlaying.value) {
                try {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            _currentPositionMs.value = mp.currentPosition
                        }
                    }
                } catch (_: Exception) {}
                delay(100L)
            }
        }
    }

    private fun pararRastreadorProgresso() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        pararAudio()
    }
}
