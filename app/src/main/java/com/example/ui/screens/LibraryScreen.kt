package com.example.ui.screens

import com.example.ui.i18n.tr

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import com.example.ui.components.AudioTrimmerDialog
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AudioItem
import com.example.domain.model.AudioType
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel

private fun intervalMinutesForAudio(audio: AudioItem, profiles: List<com.example.domain.model.Profile>): Int {
    val specificallyLinked = profiles.firstOrNull { profile ->
        profile.specificAudioId == audio.id || audio.id in profile.audioIds
    }
    val sharedProfile = profiles.firstOrNull { profile ->
        profile.specificAudioId == null && profile.audioIds.isEmpty()
    }
    return (specificallyLinked ?: sharedProfile ?: profiles.firstOrNull())
        ?.intervalMinutes
        ?.coerceIn(5, 120)
        ?: 15
}

@Composable
fun LibraryScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onNavigateToRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var editingAudio by remember { mutableStateOf<AudioItem?>(null) }
    var audioToTrim by remember { mutableStateOf<AudioItem?>(null) }
    var audioToDelete by remember { mutableStateOf<AudioItem?>(null) }
    var showTtsDialog by remember { mutableStateOf(false) }

    // Launcher para importar arquivos de áudio do sistema
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val audioPairs = uris.map { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                var displayName = "Áudio Importado"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIndex)
                        }
                    }
                } catch (_: Exception) {}

                Pair(uri.toString(), displayName)
            }
            viewModel.addAudios(audioPairs)
        }
    }

    val filteredAudios = remember(uiState.audios, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.audios
        } else {
            uiState.audios.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                        (it.ttsText != null && it.ttsText.contains(searchQuery, ignoreCase = true))
            }
        }
    }

    val activeCount = uiState.audios.count { it.isActive && !it.isMissing }
    val missingCount = uiState.audios.count { it.isMissing }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Campo de Pesquisa
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_audio_field"),
            placeholder = { Text(tr("Pesquisar áudios e lembretes...")) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Pesquisar")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpar")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Barra de Resumo e Botões de Ação
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("Biblioteca (${uiState.audios.size} áudios)"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr("$activeCount ativos"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (missingCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                        text = tr("$missingCount arquivo(s) inacessível(is)"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Linha de ações principais
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNavigateToRecord,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("record_audio_nav_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("Gravar"), fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("import_audio_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("Importar"), fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { showTtsDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_tts_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("Texto/Voz"), fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Lista de Áudios
        if (filteredAudios.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "Nenhum áudio cadastrado ainda" else "Nenhum áudio encontrado para \"$searchQuery\"",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = tr("Grave sua voz com microfone, importe músicas/MP3 ou crie lembretes de texto falado."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredAudios, key = { it.id }) { audio ->
                    val isPreviewing = uiState.previewingAudioId == audio.id

                    AudioItemCard(
                        audio = audio,
                        isPreviewing = isPreviewing,
                        onPlayPause = {
                            if (isPreviewing) {
                                viewModel.stopPreview()
                            } else {
                                viewModel.previewAudio(audio)
                            }
                        },
                        onToggleActive = { active ->
                            viewModel.updateAudio(audio.copy(isActive = active))
                        },
                        onEdit = { editingAudio = audio },
                        onDelete = { audioToDelete = audio },
                        onTrim = if (audio.audioType != AudioType.TEXT_TO_SPEECH) {
                            { audioToTrim = audio }
                        } else null
                    )
                }
            }
        }

        // Barra de Reprodução Ativa (se tocando)
        if (uiState.previewingAudioId != null) {
            val playingAudio = uiState.audios.firstOrNull { it.id == uiState.previewingAudioId }
            if (playingAudio != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("preview_player_bar"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                        text = tr("Reproduzindo prévia"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = playingAudio.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.stopPreview() },
                            modifier = Modifier.testTag("stop_preview_button")
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Parar reprodução",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo de Edição de Áudio
    editingAudio?.let { audio ->
        EditAudioDialog(
            audio = audio,
            intervalMinutes = intervalMinutesForAudio(audio, uiState.profiles),
            onDismiss = { editingAudio = null },
            onSave = { updated, intervalMinutes ->
                viewModel.updateAudio(updated, intervalMinutes)
                editingAudio = null
            },
            onOpenTrimmer = if (audio.audioType != AudioType.TEXT_TO_SPEECH) {
                {
                    val toTrim = audio
                    editingAudio = null
                    audioToTrim = toTrim
                }
            } else null
        )
    }

    // Diálogo de Aparar Áudio (Trimmer)
    audioToTrim?.let { audio ->
        AudioTrimmerDialog(
            audio = audio,
            onDismiss = { audioToTrim = null },
            onSaveTrimmed = { startMs, endMs, replaceOriginal ->
                viewModel.trimAudioItem(
                    audioItem = audio,
                    startMs = startMs,
                    endMs = endMs,
                    replaceOriginal = replaceOriginal,
                    onSuccess = {
                        audioToTrim = null
                    },
                    onError = {
                        // Error message handled via ViewModel shared flow
                    }
                )
            }
        )
    }

    // Diálogo de Confirmação de Exclusão
    audioToDelete?.let { audio ->
        AlertDialog(
            onDismissRequest = { audioToDelete = null },
            title = { Text(tr("Excluir Áudio")) },
            text = { Text(tr("Deseja realmente remover \"${audio.label}\"? Ele deixará de ser tocado nos lembretes periódicos.")) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAudio(audio.id)
                        audioToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(tr("Excluir"))
                }
            },
            dismissButton = {
                TextButton(onClick = { audioToDelete = null }) {
                    Text(tr("Cancelar"))
                }
            }
        )
    }

    // Diálogo de Criação de Voz/Texto (TTS)
    if (showTtsDialog) {
        CreateTtsAudioDialog(
            onDismiss = { showTtsDialog = false },
            onSave = { title, text, weight, volume ->
                viewModel.addAudioItem(
                    uriString = "tts:${title.lowercase().replace(" ", "_")}",
                    label = title,
                    audioType = AudioType.TEXT_TO_SPEECH,
                    ttsText = text
                )
                showTtsDialog = false
            }
        )
    }
}

@Composable
fun AudioItemCard(
    audio: AudioItem,
    isPreviewing: Boolean,
    onPlayPause: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTrim: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_card_${audio.id}"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (audio.isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ícone do tipo com botão de preview
                Surface(
                    shape = CircleShape,
                    color = if (isPreviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.testTag("preview_button_${audio.id}")
                    ) {
                        Icon(
                            imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPreviewing) "Pausar" else "Ouvir prévia",
                            tint = if (isPreviewing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Detalhes do Áudio
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = audio.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val typeLabel = when (audio.audioType) {
                            AudioType.AUDIO_FILE -> "Arquivo"
                            AudioType.RECORDING -> "Gravação"
                            AudioType.TEXT_TO_SPEECH -> "Voz/Texto"
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = typeLabel,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                        text = tr("Vol: ${audio.volumePercent}%"),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                        text = tr("Peso: ${audio.weight}"),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Switch Ativo / Inativo
                Switch(
                    checked = audio.isActive,
                    onCheckedChange = onToggleActive,
                    modifier = Modifier.testTag("toggle_audio_${audio.id}"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Se for TTS, exibe o trecho de texto
            if (!audio.ttsText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = tr("\"${audio.ttsText}\""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Barra inferior com botões de Aparar, Editar e Excluir
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onTrim != null && audio.audioType != AudioType.TEXT_TO_SPEECH) {
                    TextButton(
                        onClick = onTrim,
                        modifier = Modifier.testTag("trim_audio_btn_${audio.id}")
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr("Aparar"), fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                }

                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_audio_btn_${audio.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("Editar"), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_audio_btn_${audio.id}"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("Excluir"), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun EditAudioDialog(
    audio: AudioItem,
    intervalMinutes: Int = 15,
    onDismiss: () -> Unit,
    onSave: (AudioItem, Int) -> Unit,
    onOpenTrimmer: (() -> Unit)? = null
) {
    var label by remember(audio.id) { mutableStateOf(audio.label) }
    var volumePercent by remember(audio.id) { mutableFloatStateOf(audio.volumePercent.toFloat()) }
    var weight by remember(audio.id) { mutableIntStateOf(audio.weight) }
    var ttsText by remember(audio.id) { mutableStateOf(audio.ttsText ?: "") }
    var executionIntervalMinutes by remember(audio.id, intervalMinutes) { mutableIntStateOf(intervalMinutes) }
    val executionIntervals = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Editar Áudio")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(tr("Nome / Rótulo")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (audio.audioType == AudioType.TEXT_TO_SPEECH) {
                    OutlinedTextField(
                        value = ttsText,
                        onValueChange = { ttsText = it },
                        label = { Text(tr("Texto a Falar")) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(tr("Volume do Item"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("${volumePercent.toInt()}%"), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = volumePercent,
                        onValueChange = { volumePercent = it },
                        valueRange = 10f..100f
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(tr("Peso na Sorteio Aleatório"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("$weight"), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = weight.toFloat(),
                        onValueChange = { weight = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Frequência de execução"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Define a frequência do perfil que agenda este lembrete."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    executionIntervals.chunked(5).forEach { intervalRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            intervalRow.forEach { minutes ->
                                FilterChip(
                                    selected = executionIntervalMinutes == minutes,
                                    onClick = { executionIntervalMinutes = minutes },
                                    label = { Text(tr("${minutes}m")) },
                                    modifier = Modifier.testTag("edit_audio_interval_${minutes}")
                                )
                            }
                        }
                    }
                }


                if (audio.audioType != AudioType.TEXT_TO_SPEECH && onOpenTrimmer != null) {
                    OutlinedButton(
                        onClick = onOpenTrimmer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_dialog_trim_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Aparar Silêncio / Cortar Áudio"), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        audio.copy(
                            label = label.ifBlank { audio.label },
                            volumePercent = volumePercent.toInt(),
                            weight = weight,
                            ttsText = if (audio.audioType == AudioType.TEXT_TO_SPEECH) ttsText else audio.ttsText
                        ),
                        executionIntervalMinutes
                    )
                }
            ) {
                Text(tr("Salvar"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancelar"))
            }
        }
    )
}

@Composable
fun CreateTtsAudioDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, text: String, weight: Int, volume: Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var weight by remember { mutableIntStateOf(3) }
    var volume by remember { mutableFloatStateOf(100f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Novo Lembrete Falado (TTS)")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                        text = tr("A tecnologia Text-to-Speech do Android lerá este lembrete em voz alta nos horários programados."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("Título do Lembrete")) },
                    placeholder = { Text(tr("Ex: Postura e Água")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(tr("Texto que o celular falará")) },
                    placeholder = { Text(tr("Ex: Beba um copo de água e alongue-se.")) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(tr("Volume"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("${volume.toInt()}%"), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        valueRange = 20f..100f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && text.isNotBlank()) {
                        onSave(title.trim(), text.trim(), weight, volume.toInt())
                    }
                },
                enabled = title.isNotBlank() && text.isNotBlank()
            ) {
                Text(tr("Adicionar"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancelar"))
            }
        }
    )
}
