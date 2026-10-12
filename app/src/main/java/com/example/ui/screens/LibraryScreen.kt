package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.LembreteEntity
import com.example.domain.model.AudioItem
import com.example.domain.model.AudioType
import com.example.ui.components.AudioRecordDialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.TextToSpeechDialog
import androidx.compose.runtime.DisposableEffect
import com.example.ui.components.AudioWaveformVisualizer
import com.example.ui.components.LembreteAudioPlayer
import com.example.ui.viewmodel.LembreteViewModel
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel
import java.util.Locale

@Composable
fun LibraryScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    lembreteViewModel: LembreteViewModel = viewModel(),
    onNavigateToRecord: () -> Unit = {}
) {
    val context = LocalContext.current
    val lembretes by lembreteViewModel.lembretes.collectAsStateWithLifecycle()
    val currentlyPlayingReminderId by lembreteViewModel.currentlyPlayingReminderId.collectAsStateWithLifecycle()
    val isReminderAudioPlaying by lembreteViewModel.isPlaying.collectAsStateWithLifecycle()
    val reminderCurrentPositionMs by lembreteViewModel.currentPositionMs.collectAsStateWithLifecycle()
    val reminderDurationMs by lembreteViewModel.durationMs.collectAsStateWithLifecycle()

    // Para a reprodução ao sair da tela
    DisposableEffect(Unit) {
        onDispose {
            lembreteViewModel.pararAudio()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var editingAudio by remember { mutableStateOf<AudioItem?>(null) }
    var showRecordDialog by remember { mutableStateOf(false) }
    var showTtsDialog by remember { mutableStateOf(false) }

    // SAF Picker for multiple audio documents
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val pairs = mutableListOf<Pair<String, String>>()
            for (uri in uris) {
                // Request persistable permission
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}

                // Extract filename as default label
                var displayName = "Áudio Lembrete"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                displayName = cursor.getString(nameIndex) ?: displayName
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Remove extension if present
                val cleanedName = displayName.substringBeforeLast(".")
                pairs.add(Pair(uri.toString(), cleanedName))
            }
            viewModel.addAudios(pairs)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("library_tab_row")
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Áudios (${uiState.audios.size})", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_audios")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Lembretes Room (${lembretes.size})", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_reminders")
            )
        }

        if (selectedTab == 0) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.audios.isEmpty()) {
                    // Empty State View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                            .testTag("library_empty_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Biblioteca Vazia",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "O aplicativo não possui áudios embutidos por padrão. Você escolhe e insere seus próprios arquivos ou grava sua própria voz diretamente pelo app.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    audioPickerLauncher.launch(arrayOf("audio/*"))
                                },
                                modifier = Modifier.testTag("add_first_audios_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Importar")
                            }

                            Button(
                                onClick = onNavigateToRecord,
                                modifier = Modifier.testTag("record_first_audio_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gravar")
                            }

                            Button(
                                onClick = { showTtsDialog = true },
                                modifier = Modifier.testTag("tts_first_audio_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Texto (TTS)")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Biblioteca de Áudios",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${uiState.audios.size} itens (${uiState.audios.count { it.isActive && !it.isMissing }} ativos)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { showTtsDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("add_tts_audio_button")
                                    ) {
                                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Texto (TTS)")
                                    }

                                    Button(
                                        onClick = onNavigateToRecord,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("record_audio_button")
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Gravar")
                                    }

                                    Button(
                                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("add_more_audios_button")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Importar")
                                    }
                                }
                            }
                        }

                        items(uiState.audios, key = { it.id }) { audio ->
                            AudioCardItem(
                                audio = audio,
                                isPreviewing = uiState.previewingAudioId == audio.id,
                                onToggleActive = { active ->
                                    viewModel.updateAudio(audio.copy(isActive = active))
                                },
                                onPreview = { viewModel.previewAudio(audio) },
                                onEdit = { editingAudio = audio },
                                onDelete = { viewModel.deleteAudio(audio.id) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
                }
            }
        } else {
            // Tab 1: Lembretes Gravados do Room (LembreteDao)
            Box(modifier = Modifier.fillMaxSize()) {
                if (lembretes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                            .testTag("reminders_empty_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Nenhum Lembrete no Room",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Grave novos lembretes em áudio com a MediaRecorder API. Os caminhos de áudio, periodicidade e Horários de Descanso serão salvos no banco Room.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = onNavigateToRecord,
                            modifier = Modifier.testTag("record_first_reminder_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gravar Lembrete de Áudio")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Lembretes Locais (Room)",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${lembretes.size} lembretes cadastrados (${lembretes.count { it.isEnabled }} ativos)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = onNavigateToRecord,
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .testTag("record_new_reminder_button"),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Novo Lembrete")
                                    }

                                    Button(
                                        onClick = { lembreteViewModel.testarWorkManagerImediato() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .testTag("test_worker_button"),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Testar Worker")
                                    }

                                    Button(
                                        onClick = { lembreteViewModel.limparTodosLembretes() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        modifier = Modifier.testTag("clear_all_reminders_button"),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Limpar")
                                    }
                                }
                            }
                        }

                        // Banner do áudio sendo reproduzido no momento
                        if (currentlyPlayingReminderId != null) {
                            val activePlayingReminder = lembretes.firstOrNull { it.id == currentlyPlayingReminderId }
                            if (activePlayingReminder != null) {
                                item {
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp)
                                            .testTag("active_playback_header_banner"),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.elevatedCardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                AudioWaveformVisualizer(isPlaying = isReminderAudioPlaying)
                                                Column {
                                                    Text(
                                                        text = "Ouvindo agora:",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                                    )
                                                    Text(
                                                        text = activePlayingReminder.title,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { lembreteViewModel.alternarReproducao(activePlayingReminder) },
                                                    modifier = Modifier.testTag("banner_play_pause_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isReminderAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                        contentDescription = if (isReminderAudioPlaying) "Pausar" else "Continuar",
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { lembreteViewModel.pararAudio() },
                                                    modifier = Modifier.testTag("banner_stop_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Stop,
                                                        contentDescription = "Parar",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        items(lembretes, key = { it.id }) { lembrete ->
                            val isPlayingThis = currentlyPlayingReminderId == lembrete.id && isReminderAudioPlaying
                            val isPausedThis = currentlyPlayingReminderId == lembrete.id && !isReminderAudioPlaying && reminderCurrentPositionMs > 0

                            LembreteCardItem(
                                lembrete = lembrete,
                                isPlayingThis = isPlayingThis,
                                isPausedThis = isPausedThis,
                                currentPositionMs = if (currentlyPlayingReminderId == lembrete.id) reminderCurrentPositionMs else 0,
                                durationMs = if (currentlyPlayingReminderId == lembrete.id) reminderDurationMs else 0,
                                onPlayPause = { lembreteViewModel.alternarReproducao(lembrete) },
                                onStop = { lembreteViewModel.pararAudio() },
                                onSeekTo = { pos -> lembreteViewModel.buscarPosicao(pos) },
                                onSkip = { secs -> lembreteViewModel.pularTempo(secs) },
                                onRecordAudio = onNavigateToRecord,
                                onToggleEnabled = { enabled ->
                                    lembreteViewModel.alternarStatusLembrete(lembrete.id, enabled)
                                },
                                onDelete = {
                                    if (currentlyPlayingReminderId == lembrete.id) {
                                        lembreteViewModel.pararAudio()
                                    }
                                    lembreteViewModel.deletarLembrete(lembrete)
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
                }
            }
        }
    }

    // Audio Edit Dialog
    editingAudio?.let { audio ->
        AudioEditDialog(
            audio = audio,
            onDismiss = { editingAudio = null },
            onConfirm = { updated ->
                viewModel.updateAudio(updated)
                editingAudio = null
            }
        )
    }

    // Audio Record Dialog with MediaRecorder API & RECORD_AUDIO permission
    if (showRecordDialog) {
        AudioRecordDialog(
            onDismiss = { showRecordDialog = false },
            onAudioSaved = { uriString, label, file, periodicity ->
                lembreteViewModel.salvarLembreteGravado(
                    titulo = label,
                    arquivoAudio = file,
                    periodicidadeMinutos = periodicity
                )
                showRecordDialog = false
            }
        )
    }

    // Text-To-Speech Dialog
    if (showTtsDialog) {
        TextToSpeechDialog(
            onDismiss = { showTtsDialog = false },
            onSave = { uriString, label, text ->
                viewModel.addAudioItem(
                    uriString = uriString,
                    label = label,
                    audioType = AudioType.TEXT_TO_SPEECH,
                    ttsText = text
                )
                showTtsDialog = false
            }
        )
    }
}

@Composable
fun AudioCardItem(
    audio: AudioItem,
    isPreviewing: Boolean,
    onToggleActive: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_card_${audio.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (audio.isMissing) {
                Color(0xFFFFEBEE)
            } else if (!audio.isActive) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Preview button
                IconButton(
                    onClick = onPreview,
                    enabled = !audio.isMissing,
                    modifier = Modifier.testTag("preview_audio_${audio.id}")
                ) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPreviewing) "Parar prévia" else "Pré-ouvir",
                        tint = if (isPreviewing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = audio.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (audio.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )

                    if (!audio.ttsText.isNullOrBlank()) {
                        Text(
                            text = "\"${audio.ttsText}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            maxLines = 2
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // TTS badge
                        if (audio.audioType == AudioType.TEXT_TO_SPEECH) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Voz TTS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        // Weight badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Peso ${audio.weight}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        // Volume badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Vol: ${audio.volumePercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        // Missing file badge
                        if (audio.isMissing) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Inacessível",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }

                // Active switch
                Switch(
                    checked = audio.isActive,
                    onCheckedChange = onToggleActive,
                    enabled = !audio.isMissing,
                    modifier = Modifier.testTag("audio_switch_${audio.id}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Editar")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AudioEditDialog(
    audio: AudioItem,
    onDismiss: () -> Unit,
    onConfirm: (AudioItem) -> Unit
) {
    var label by remember { mutableStateOf(audio.label) }
    var weight by remember { mutableIntStateOf(audio.weight) }
    var volume by remember { mutableIntStateOf(audio.volumePercent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Áudio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Rótulo do áudio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!audio.ttsText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Texto falado (TTS):",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "\"${audio.ttsText}\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = "Peso para sorteio: $weight (de 1 a 5)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = weight.toFloat(),
                        onValueChange = { weight = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }

                Column {
                    Text(
                        text = "Volume individual: $volume%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { volume = it.toInt() },
                        valueRange = 0f..100f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        audio.copy(
                            label = label.ifBlank { "Áudio sem título" },
                            weight = weight,
                            volumePercent = volume
                        )
                    )
                }
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun LembreteCardItem(
    lembrete: LembreteEntity,
    isPlayingThis: Boolean,
    isPausedThis: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onRecordAudio: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lembrete_item_${lembrete.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lembrete.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!lembrete.text.isNullOrBlank()) {
                        Text(
                            text = lembrete.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = lembrete.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("lembrete_switch_${lembrete.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Periodicity Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "A cada ${lembrete.periodicityMinutes}m",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Audio Type Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (lembrete.audioType == "RECORDING") "Gravação de Voz" else "Áudio",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Active Time Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:%02d - %02d:%02d", lembrete.startHour, lembrete.startMinute, lembrete.endHour, lembrete.endMinute),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Resting period card banner
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            "Descanso: %02d:%02d às %02d:%02d (Sem emissão de lembretes)",
                            lembrete.endHour,
                            lembrete.endMinute,
                            lembrete.startHour,
                            lembrete.startMinute
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Componente de Player de Áudio integrado para ouvir a gravação salva
            LembreteAudioPlayer(
                lembrete = lembrete,
                isPlayingThis = isPlayingThis,
                isPausedThis = isPausedThis,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onPlayPause = onPlayPause,
                onStop = onStop,
                onSeekTo = onSeekTo,
                onSkip = onSkip,
                onRecordAudio = onRecordAudio
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_lembrete_${lembrete.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

