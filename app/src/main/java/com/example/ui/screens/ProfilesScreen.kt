package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import com.example.domain.scheduler.ScheduleCalculator
import com.example.ui.components.SimpleTimePickerDialog
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ProfilesScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    var profileToEdit by remember { mutableStateOf<Profile?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Perfis de Funcionamento",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure regras, dias, intervalos e descansos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("create_profile_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Novo")
                }
            }
        }

        if (uiState.profiles.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Nenhum perfil cadastrado",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Crie um perfil para definir os dias e horários de reprodução.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showCreateDialog = true }) {
                            Text("Criar primeiro perfil")
                        }
                    }
                }
            }
        } else {
            items(uiState.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onToggleEnabled = { enabled ->
                        viewModel.toggleProfileEnabled(profile.id, enabled)
                    },
                    onDuplicate = { viewModel.duplicateProfile(profile) },
                    onEdit = { profileToEdit = profile },
                    onDelete = { viewModel.deleteProfile(profile.id) }
                )
            }
        }
    }

    // Profile Editor Dialog
    if (showCreateDialog) {
        ProfileEditorDialog(
            profile = null,
            allAudios = uiState.audios,
            onDismiss = { showCreateDialog = false },
            onSave = { newProfile ->
                viewModel.saveProfile(newProfile)
                showCreateDialog = false
            }
        )
    }

    profileToEdit?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            allAudios = uiState.audios,
            onDismiss = { profileToEdit = null },
            onSave = { updatedProfile ->
                viewModel.saveProfile(updatedProfile)
                profileToEdit = null
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: Profile,
    onToggleEnabled: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (profile.isEnabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Janela: ${profile.startTime.format(timeFormatter)} às ${profile.endTime.format(timeFormatter)} • Cada ${profile.intervalMinutes} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("profile_switch_${profile.id}")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Days of week indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val days = listOf(
                    DayOfWeek.SUNDAY to "D",
                    DayOfWeek.MONDAY to "S",
                    DayOfWeek.TUESDAY to "T",
                    DayOfWeek.WEDNESDAY to "Q",
                    DayOfWeek.THURSDAY to "Q",
                    DayOfWeek.FRIDAY to "S",
                    DayOfWeek.SATURDAY to "S"
                )

                days.forEach { (day, label) ->
                    val isSelected = profile.daysOfWeek.contains(day)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Resting periods summary
            if (profile.restingPeriods.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${profile.restingPeriods.size} período(s) de descanso configurado(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicar perfil")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar perfil")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir perfil", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(
    profile: Profile?,
    allAudios: List<AudioItem>,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    var name by remember { mutableStateOf(profile?.name ?: "Novo Perfil") }
    val selectedDays = remember {
        mutableStateListOf<DayOfWeek>().apply {
            addAll(profile?.daysOfWeek ?: listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        }
    }
    var startTime by remember { mutableStateOf(profile?.startTime ?: LocalTime.of(8, 0)) }
    var endTime by remember { mutableStateOf(profile?.endTime ?: LocalTime.of(22, 0)) }
    var intervalMinutes by remember { mutableIntStateOf(profile?.intervalMinutes ?: 15) }
    var playbackOrder by remember { mutableStateOf(profile?.playbackOrder ?: PlaybackOrder.SEQUENTIAL) }
    var specificAudioId by remember { mutableStateOf(profile?.specificAudioId) }
    var resumePolicy by remember { mutableStateOf(profile?.resumePolicy ?: ResumePolicy.NEXT_GRID_MARK) }
    var priority by remember { mutableIntStateOf(profile?.priority ?: 0) }

    val restingPeriods = remember {
        mutableStateListOf<RestingPeriod>().apply {
            addAll(profile?.restingPeriods ?: emptyList())
        }
    }

    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    // Dialog for adding a rest
    var showAddRestDialog by remember { mutableStateOf(false) }

    // Validate rests against operating hours
    val tempProfile = Profile(
        name = name,
        startTime = startTime,
        endTime = endTime,
        restingPeriods = restingPeriods
    )
    val restsCoverAll = ScheduleCalculator.doRestsCoverEntireOperatingHours(tempProfile)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (profile == null) "Novo Perfil" else "Editar Perfil",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome do Perfil") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Days of week
                    Column {
                        Text("Dias da Semana", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val allDays = listOf(
                                DayOfWeek.SUNDAY to "Dom",
                                DayOfWeek.MONDAY to "Seg",
                                DayOfWeek.TUESDAY to "Ter",
                                DayOfWeek.WEDNESDAY to "Qua",
                                DayOfWeek.THURSDAY to "Qui",
                                DayOfWeek.FRIDAY to "Sex",
                                DayOfWeek.SATURDAY to "Sáb"
                            )
                            allDays.forEach { (day, label) ->
                                val selected = selectedDays.contains(day)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (selected) {
                                            if (selectedDays.size > 1) selectedDays.remove(day)
                                        } else {
                                            selectedDays.add(day)
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    // Operating hours (Início e Fim)
                    Column {
                        Text("Horário de Funcionamento", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Pode atravessar a meia-noite (ex: 22:00 às 06:00).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showStartTimePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Início", style = MaterialTheme.typography.labelSmall)
                                    Text(startTime.format(timeFormatter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            OutlinedButton(
                                onClick = { showEndTimePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Fim", style = MaterialTheme.typography.labelSmall)
                                    Text(endTime.format(timeFormatter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Interval in minutes with shortcuts
                    Column {
                        Text("Intervalo Fixo: $intervalMinutes min", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5, 10, 15, 30, 60).forEach { mins ->
                                FilterChip(
                                    selected = intervalMinutes == mins,
                                    onClick = { intervalMinutes = mins },
                                    label = { Text("$mins min") }
                                )
                            }
                        }
                    }

                    // Playback order
                    Column {
                        Text("Ordem de Reprodução", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = playbackOrder == PlaybackOrder.SEQUENTIAL,
                                onClick = { playbackOrder = PlaybackOrder.SEQUENTIAL }
                            )
                            Text("Sequencial")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = playbackOrder == PlaybackOrder.RANDOM,
                                onClick = { playbackOrder = PlaybackOrder.RANDOM }
                            )
                            Text("Aleatória (respeita pesos)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = playbackOrder == PlaybackOrder.SPECIFIC,
                                onClick = { playbackOrder = PlaybackOrder.SPECIFIC }
                            )
                            Text("Áudio específico")
                        }
                    }

                    // Specific audio choice
                    if (playbackOrder == PlaybackOrder.SPECIFIC && allAudios.isNotEmpty()) {
                        Column {
                            Text("Selecione o áudio específico:", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                allAudios.forEach { audio ->
                                    FilterChip(
                                        selected = specificAudioId == audio.id,
                                        onClick = { specificAudioId = audio.id },
                                        label = { Text(audio.label) }
                                    )
                                }
                            }
                        }
                    }

                    // Resume rule
                    Column {
                        Text("Retomada após Descanso", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = resumePolicy == ResumePolicy.NEXT_GRID_MARK,
                                onClick = { resumePolicy = ResumePolicy.NEXT_GRID_MARK }
                            )
                            Text("Próxima marca da grade (padrão)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = resumePolicy == ResumePolicy.AT_REST_END,
                                onClick = { resumePolicy = ResumePolicy.AT_REST_END }
                            )
                            Text("Retomar ao fim do descanso")
                        }
                    }

                    // Resting periods
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Horários de Descanso", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Nenhum áudio toca durante o descanso.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = { showAddRestDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Adicionar")
                            }
                        }

                        if (restsCoverAll) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Aviso: Os descansos cobrem todo o horário de funcionamento. Nenhum áudio tocará!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        restingPeriods.forEachIndexed { index, rest ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = if (rest.label.isNotBlank()) rest.label else "Descanso ${index + 1}",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "${rest.startTime.format(timeFormatter)} às ${rest.endTime.format(timeFormatter)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { restingPeriods.removeAt(index) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remover descanso", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val merged = ScheduleCalculator.mergeRestingPeriods(restingPeriods)
                            onSave(
                                Profile(
                                    id = profile?.id ?: 0L,
                                    name = name.ifBlank { "Perfil" },
                                    isEnabled = profile?.isEnabled ?: true,
                                    priority = priority,
                                    daysOfWeek = selectedDays.toSet(),
                                    startTime = startTime,
                                    endTime = endTime,
                                    intervalMinutes = intervalMinutes,
                                    playbackOrder = playbackOrder,
                                    specificAudioId = specificAudioId,
                                    restingPeriods = merged,
                                    resumePolicy = resumePolicy
                                )
                            )
                        }
                    ) {
                        Text("Salvar Perfil")
                    }
                }
            }
        }
    }

    // Time Pickers
    if (showStartTimePicker) {
        SimpleTimePickerDialog(
            title = "Início do Funcionamento",
            initialTime = startTime,
            onConfirm = {
                startTime = it
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false }
        )
    }

    if (showEndTimePicker) {
        SimpleTimePickerDialog(
            title = "Fim do Funcionamento",
            initialTime = endTime,
            onConfirm = {
                endTime = it
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }

    // Add Rest Dialog
    if (showAddRestDialog) {
        AddRestPeriodDialog(
            onDismiss = { showAddRestDialog = false },
            onConfirm = { newRest ->
                restingPeriods.add(newRest)
                showAddRestDialog = false
            }
        )
    }
}

@Composable
fun AddRestPeriodDialog(
    onDismiss: () -> Unit,
    onConfirm: (RestingPeriod) -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    var label by remember { mutableStateOf("Almoço") }
    var restStart by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var restEnd by remember { mutableStateOf(LocalTime.of(13, 0)) }

    var showPickerStart by remember { mutableStateOf(false) }
    var showPickerEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo Período de Descanso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Rótulo (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showPickerStart = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Início", style = MaterialTheme.typography.labelSmall)
                            Text(restStart.format(timeFormatter), fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = { showPickerEnd = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Fim", style = MaterialTheme.typography.labelSmall)
                            Text(restEnd.format(timeFormatter), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        RestingPeriod(
                            label = label.ifBlank { "Descanso" },
                            startTime = restStart,
                            endTime = restEnd
                        )
                    )
                }
            ) {
                Text("Adicionar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )

    if (showPickerStart) {
        SimpleTimePickerDialog(
            title = "Início do Descanso",
            initialTime = restStart,
            onConfirm = {
                restStart = it
                showPickerStart = false
            },
            onDismiss = { showPickerStart = false }
        )
    }

    if (showPickerEnd) {
        SimpleTimePickerDialog(
            title = "Fim do Descanso",
            initialTime = restEnd,
            onConfirm = {
                restEnd = it
                showPickerEnd = false
            },
            onDismiss = { showPickerEnd = false }
        )
    }
}
