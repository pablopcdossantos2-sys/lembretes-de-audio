package com.example.ui.screens

import com.example.ui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import com.example.ui.components.SimpleTimePickerDialog
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Aviso de conflito de dias entre perfis habilitados
            if (!uiState.profileDayConflictWarning.isNullOrBlank()) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("profile_conflict_warning")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.profileDayConflictWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Cabeçalho da Lista
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tr("Perfis de Programação"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr("Defina horários, frequências e períodos de descanso"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { isCreatingNew = true },
                    modifier = Modifier.testTag("create_profile_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(tr("Novo Perfil"), fontSize = 13.sp)
                }
            }

            // Lista de Perfis
            if (uiState.profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                        text = tr("Nenhum perfil cadastrado"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            onToggleEnabled = { enabled ->
                                viewModel.toggleProfileEnabled(profile.id, enabled)
                            },
                            onEdit = { editingProfile = profile },
                            onDuplicate = { viewModel.duplicateProfile(profile) },
                            onDelete = { profileToDelete = profile }
                        )
                    }
                }
            }
        }

        // Diálogo de criação/edição de perfil
        if (isCreatingNew || editingProfile != null) {
            val targetProfile = editingProfile ?: Profile(
                id = 0L,
                name = "Novo Perfil",
                isEnabled = true,
                priority = (uiState.profiles.maxOfOrNull { it.priority } ?: 0) + 1,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(18, 0),
                intervalMinutes = 15,
                playbackOrder = PlaybackOrder.SEQUENTIAL,
                restingPeriods = listOf(
                    RestingPeriod(
                        id = 0L,
                        label = "Almoço",
                        startTime = LocalTime.of(12, 0),
                        endTime = LocalTime.of(13, 0)
                    )
                ),
                resumePolicy = ResumePolicy.NEXT_GRID_MARK
            )

            ProfileEditDialog(
                profile = targetProfile,
                onDismiss = {
                    isCreatingNew = false
                    editingProfile = null
                },
                onSave = { updated ->
                    viewModel.saveProfile(updated)
                    isCreatingNew = false
                    editingProfile = null
                }
            )
        }

        // Confirmação de exclusão
        profileToDelete?.let { profile ->
            AlertDialog(
                onDismissRequest = { profileToDelete = null },
                title = { Text(tr("Excluir Perfil")) },
                text = { Text(tr("Deseja realmente remover o perfil \"${profile.name}\"? Todos os horários e períodos de descanso associados serão excluídos.")) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteProfile(profile.id)
                            profileToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(tr("Excluir"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { profileToDelete = null }) {
                        Text(tr("Cancelar"))
                    }
                }
            )
        }
    }
}

@Composable
fun ProfileCard(
    profile: Profile,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (profile.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Linha do título, prioridade e switch de ativação
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                        text = tr("P${profile.priority}"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("toggle_profile_${profile.id}"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dias da semana
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val days = listOf(
                    Pair(DayOfWeek.MONDAY, "Seg"),
                    Pair(DayOfWeek.TUESDAY, "Ter"),
                    Pair(DayOfWeek.WEDNESDAY, "Qua"),
                    Pair(DayOfWeek.THURSDAY, "Qui"),
                    Pair(DayOfWeek.FRIDAY, "Sex"),
                    Pair(DayOfWeek.SATURDAY, "Sáb"),
                    Pair(DayOfWeek.SUNDAY, "Dom")
                )

                days.forEach { (day, label) ->
                    val isDayActive = profile.daysOfWeek.contains(day)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDayActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = if (isDayActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDayActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Horário de funcionamento e periodicidade
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        "%02d:%02d às %02d:%02d • a cada %d min",
                        profile.startTime.hour,
                        profile.startTime.minute,
                        profile.endTime.hour,
                        profile.endTime.minute,
                        profile.intervalMinutes
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // Horários de Descanso (Se houver)
            if (profile.restingPeriods.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                        text = tr("Horários de Descanso:"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    profile.restingPeriods.forEach { rest ->
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                "• %s (%02d:%02d às %02d:%02d)",
                                rest.label,
                                rest.startTime.hour,
                                rest.startTime.minute,
                                rest.endTime.hour,
                                rest.endTime.minute
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ações: Editar, Duplicar, Excluir
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDuplicate,
                    modifier = Modifier.testTag("duplicate_profile_btn_${profile.id}")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("Duplicar"), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(4.dp))

                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_profile_btn_${profile.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("Editar"), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(4.dp))

                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_profile_btn_${profile.id}"),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var priority by remember { mutableIntStateOf(profile.priority) }
    val selectedDays = remember { mutableStateListOf(*profile.daysOfWeek.toTypedArray()) }
    var startTime by remember { mutableStateOf(profile.startTime) }
    var endTime by remember { mutableStateOf(profile.endTime) }
    var intervalMinutes by remember { mutableIntStateOf(profile.intervalMinutes) }
    var playbackOrder by remember { mutableStateOf(profile.playbackOrder) }
    var resumePolicy by remember { mutableStateOf(profile.resumePolicy) }
    var fadeInOverride by remember { mutableStateOf(profile.fadeInOverrideEnabled) }
    var fadeInDuration by remember { mutableFloatStateOf(profile.fadeInDurationSeconds) }

    val restingPeriods = remember {
        mutableStateListOf(*profile.restingPeriods.map { it.copy() }.toTypedArray())
    }

    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    // Estado para adicionar novo período de descanso
    var showAddRestDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.id == 0L) "Novo Perfil de Lembretes" else "Editar Perfil") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Nome do Perfil")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Prioridade
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(tr("Prioridade de Execução"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("$priority"), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = priority.toFloat(),
                        onValueChange = { priority = it.toInt() },
                        valueRange = 1f..20f,
                        steps = 18
                    )
                }

                // Dias da semana
                Column {
                    Text(tr("Dias da Semana Ativos"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val allDays = listOf(
                            Pair(DayOfWeek.MONDAY, "Seg"),
                            Pair(DayOfWeek.TUESDAY, "Ter"),
                            Pair(DayOfWeek.WEDNESDAY, "Qua"),
                            Pair(DayOfWeek.THURSDAY, "Qui"),
                            Pair(DayOfWeek.FRIDAY, "Sex"),
                            Pair(DayOfWeek.SATURDAY, "Sáb"),
                            Pair(DayOfWeek.SUNDAY, "Dom")
                        )
                        allDays.forEach { (day, label) ->
                            val selected = selectedDays.contains(day)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (selected) selectedDays.remove(day) else selectedDays.add(day)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                // Horário de Início e Fim
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("Início"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", startTime.hour, startTime.minute),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("Fim"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", endTime.hour, endTime.minute),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Periodicidade
                Column {
                    Text(tr("Intervalo entre disparos"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val intervals = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
                        intervals.forEach { mins ->
                            FilterChip(
                                selected = intervalMinutes == mins,
                                onClick = { intervalMinutes = mins },
                                label = { Text(tr("${mins}m")) }
                            )
                        }
                    }
                }

                // Seção: Horários de Descanso (Resting Periods)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Horários de Descanso"),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            TextButton(onClick = { showAddRestDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(tr("Adicionar"), fontSize = 12.sp)
                            }
                        }

                        if (restingPeriods.isEmpty()) {
                            Text(
                        text = tr("Nenhum horário de descanso adicionado. Os lembretes tocarão continuamente no horário ativo."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            restingPeriods.forEachIndexed { index, rest ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(
                                            Locale.getDefault(),
                                            "%s (%02d:%02d - %02d:%02d)",
                                            rest.label,
                                            rest.startTime.hour,
                                            rest.startTime.minute,
                                            rest.endTime.hour,
                                            rest.endTime.minute
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    IconButton(
                                        onClick = { restingPeriods.removeAt(index) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remover descanso",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Ordem de Reprodução
                Column {
                    Text(tr("Modo de Reprodução"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = playbackOrder == PlaybackOrder.SEQUENTIAL,
                            onClick = { playbackOrder = PlaybackOrder.SEQUENTIAL },
                            label = { Text(tr("Sequencial")) }
                        )
                        FilterChip(
                            selected = playbackOrder == PlaybackOrder.RANDOM,
                            onClick = { playbackOrder = PlaybackOrder.RANDOM },
                            label = { Text(tr("Aleatório")) }
                        )
                    }
                }

                // Fade-in Override
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(tr("Sobrescrever Fade-in"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("Aumenta o volume gradualmente neste perfil"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = fadeInOverride, onCheckedChange = { fadeInOverride = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.ifBlank { "Perfil" },
                            priority = priority,
                            daysOfWeek = selectedDays.toSet(),
                            startTime = startTime,
                            endTime = endTime,
                            intervalMinutes = intervalMinutes,
                            playbackOrder = playbackOrder,
                            restingPeriods = restingPeriods.toList(),
                            resumePolicy = resumePolicy,
                            fadeInOverrideEnabled = fadeInOverride,
                            fadeInDurationSeconds = fadeInDuration
                        )
                    )
                }
            ) {
                Text(tr("Salvar Perfil"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancelar"))
            }
        }
    )

    if (showStartTimePicker) {
        SimpleTimePickerDialog(
            title = "Horário de Início do Perfil",
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
            title = "Horário de Fim do Perfil",
            initialTime = endTime,
            onConfirm = {
                endTime = it
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }

    if (showAddRestDialog) {
        AddRestingPeriodDialog(
            onDismiss = { showAddRestDialog = false },
            onAdd = { label, sTime, eTime ->
                restingPeriods.add(
                    RestingPeriod(
                        id = 0L,
                        label = label,
                        startTime = sTime,
                        endTime = eTime
                    )
                )
                showAddRestDialog = false
            }
        )
    }
}

@Composable
fun AddRestingPeriodDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, startTime: LocalTime, endTime: LocalTime) -> Unit
) {
    var label by remember { mutableStateOf("Almoço") }
    var restStart by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var restEnd by remember { mutableStateOf(LocalTime.of(13, 0)) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Adicionar Horário de Descanso")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(tr("Nome da Pausa / Descanso")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("Início Pausa"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", restStart.hour, restStart.minute),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("Fim Pausa"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", restEnd.hour, restEnd.minute),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(label.ifBlank { "Descanso" }, restStart, restEnd)
                }
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

    if (showStartPicker) {
        SimpleTimePickerDialog(
            title = "Início do Descanso",
            initialTime = restStart,
            onConfirm = {
                restStart = it
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showEndPicker) {
        SimpleTimePickerDialog(
            title = "Fim do Descanso",
            initialTime = restEnd,
            onConfirm = {
                restEnd = it
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}
