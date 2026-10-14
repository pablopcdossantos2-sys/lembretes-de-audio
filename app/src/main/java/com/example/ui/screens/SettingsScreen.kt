package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.backup.ImportResult
import com.example.domain.scheduler.ScheduleCalculator
import com.example.ui.components.SimpleTimePickerDialog
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel
import com.example.util.DeviceStateHelper
import com.example.worker.ReminderWorkScheduler
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    showBatteryDialogDefault: Boolean = false,
    onDismissBatteryDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSettingsTab by remember { mutableIntStateOf(0) }
    var showBatteryAssistantDialog by remember { mutableStateOf(showBatteryDialogDefault) }
    var importResultDialog by remember { mutableStateOf<ImportResult?>(null) }

    // Diálogos de Seleção de Horário de Início e Fim do Descanso
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val prefs = uiState.preferences

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val outputStream = context.contentResolver.openOutputStream(it)
                if (outputStream != null) {
                    val success = viewModel.exportBackup(outputStream)
                    importResultDialog = ImportResult(
                        success = success,
                        errorMessage = if (!success) "Falha ao gravar arquivo de backup." else null
                    )
                }
            }
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) {
                    val result = viewModel.importBackup(inputStream)
                    importResultDialog = result
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Abas principais de navegação nas configurações
        TabRow(
            selectedTabIndex = selectedSettingsTab,
            modifier = Modifier.testTag("settings_tab_row")
        ) {
            Tab(
                selected = selectedSettingsTab == 0,
                onClick = { selectedSettingsTab = 0 },
                text = { Text("Descanso e Não Perturbe") },
                icon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
                modifier = Modifier.testTag("tab_quiet_hours")
            )
            Tab(
                selected = selectedSettingsTab == 1,
                onClick = { selectedSettingsTab = 1 },
                text = { Text("Áudio e Geral") },
                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                modifier = Modifier.testTag("tab_general_audio")
            )
        }

        if (selectedSettingsTab == 0) {
            // ==========================================
            // ABA 0: HORÁRIOS DE DESCANSO & NÃO PERTURBE
            // ==========================================
            QuietHoursSettingsContent(
                uiState = uiState,
                viewModel = viewModel,
                onOpenStartTimePicker = { showStartTimePicker = true },
                onOpenEndTimePicker = { showEndTimePicker = true }
            )
        } else {
            // ==========================================
            // ABA 1: ÁUDIO, BATERIA E BACKUP GERAL
            // ==========================================
            GeneralSettingsContent(
                uiState = uiState,
                viewModel = viewModel,
                onOpenBatteryDialog = { showBatteryAssistantDialog = true },
                onExportBackup = { exportLauncher.launch("lembretes_audio_backup.json") },
                onImportBackup = { importLauncher.launch(arrayOf("application/json", "*/*")) }
            )
        }
    }

    // TimePicker para Horário de Início do Descanso
    if (showStartTimePicker) {
        SimpleTimePickerDialog(
            title = "Horário de Início do Descanso / Não Perturbe",
            initialTime = LocalTime.of(prefs.quietHoursStartHour, prefs.quietHoursStartMinute),
            onConfirm = { time ->
                viewModel.updateQuietHours(
                    enabled = prefs.quietHoursEnabled,
                    startHour = time.hour,
                    startMinute = time.minute,
                    endHour = prefs.quietHoursEndHour,
                    endMinute = prefs.quietHoursEndMinute,
                    daysCsv = prefs.quietHoursDaysCsv,
                    notifySilently = prefs.quietHoursNotifySilently
                )
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false }
        )
    }

    // TimePicker para Horário de Término do Descanso
    if (showEndTimePicker) {
        SimpleTimePickerDialog(
            title = "Horário de Término do Descanso / Não Perturbe",
            initialTime = LocalTime.of(prefs.quietHoursEndHour, prefs.quietHoursEndMinute),
            onConfirm = { time ->
                viewModel.updateQuietHours(
                    enabled = prefs.quietHoursEnabled,
                    startHour = prefs.quietHoursStartHour,
                    startMinute = prefs.quietHoursStartMinute,
                    endHour = time.hour,
                    endMinute = time.minute,
                    daysCsv = prefs.quietHoursDaysCsv,
                    notifySilently = prefs.quietHoursNotifySilently
                )
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }

    // Battery Assistant Dialog
    if (showBatteryAssistantDialog) {
        BatteryAssistantDialog(
            isIgnored = uiState.isBatteryOptimizationIgnored,
            onDismiss = {
                showBatteryAssistantDialog = false
                onDismissBatteryDialog()
            },
            onRequestIgnore = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(fallbackIntent)
                    } catch (_: Exception) {}
                }
            }
        )
    }

    // Import / Export Feedback Dialog
    importResultDialog?.let { result ->
        AlertDialog(
            onDismissRequest = { importResultDialog = null },
            title = {
                Text(if (result.success) "Operação Concluída" else "Erro")
            },
            text = {
                Column {
                    if (result.success) {
                        Text("Backup processado com sucesso!")
                        if (result.profilesImported > 0 || result.audiosImported > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• ${result.profilesImported} perfis importados")
                            Text("• ${result.audiosImported} referências de áudios importadas")
                        }
                        if (result.missingAudioLabels.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Atenção: Os seguintes áudios não foram encontrados no aparelho e precisarão ser reassociados na biblioteca:\n- ${result.missingAudioLabels.joinToString("\n- ")}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Text(result.errorMessage ?: "Ocorreu um erro ao processar o arquivo.")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { importResultDialog = null }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * Conteúdo detalhado para a configuração de Horários de Descanso e Não Perturbe,
 * com integração direta ao agendamento de lembretes pelo Android WorkManager.
 */
@Composable
fun QuietHoursSettingsContent(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onOpenStartTimePicker: () -> Unit,
    onOpenEndTimePicker: () -> Unit
) {
    val context = LocalContext.current
    val prefs = uiState.preferences

    val now = remember { ZonedDateTime.now() }
    val isQuietNow = prefs.quietHoursEnabled && ScheduleCalculator.isTimeInQuietHours(
        time = now.toLocalTime(),
        dayOfWeek = now.dayOfWeek,
        enabled = prefs.quietHoursEnabled,
        startHour = prefs.quietHoursStartHour,
        startMinute = prefs.quietHoursStartMinute,
        endHour = prefs.quietHoursEndHour,
        endMinute = prefs.quietHoursEndMinute,
        daysOfWeekCsv = prefs.quietHoursDaysCsv
    )
    val isSystemDnd = prefs.respectDoNotDisturb && DeviceStateHelper.isDoNotDisturbActive(context)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Status em Tempo Real
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quiet_hours_status_banner"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isQuietNow || isSystemDnd) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isQuietNow || isSystemDnd) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isQuietNow || isSystemDnd) Icons.Default.Bedtime else Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isQuietNow) {
                                "Horário de Descanso Ativo"
                            } else if (isSystemDnd) {
                                "Modo Não Perturbe do Sistema Ativo"
                            } else {
                                "Horário de Lembretes Ativo"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isQuietNow) {
                                "Lembretes silenciados no WorkManager até as %02d:%02d.".format(
                                    Locale.getDefault(),
                                    prefs.quietHoursEndHour,
                                    prefs.quietHoursEndMinute
                                )
                            } else if (isSystemDnd) {
                                "Lembretes pausados respeitando o modo silencioso do Android."
                            } else {
                                "Lembretes em áudio sendo disparados normalmente em segundo plano."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. Card Principal: Horário de Não Perturbe Programado
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_scheduled_quiet_hours"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Não Perturbe Programado",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Silencia o áudio dos lembretes durante o repouso",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = prefs.quietHoursEnabled,
                            onCheckedChange = { checked ->
                                viewModel.toggleQuietHoursEnabled(checked)
                            },
                            modifier = Modifier.testTag("toggle_quiet_hours")
                        )
                    }

                    AnimatedVisibility(visible = prefs.quietHoursEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            HorizontalDivider()

                            // Seletor Interativo de Horários (Início e Fim)
                            Text(
                                text = "Janela de Descanso Diária",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Card Início
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onOpenStartTimePicker() }
                                        .testTag("quiet_hours_start_time_picker"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Início do Repouso",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = String.format(
                                                    Locale.getDefault(),
                                                    "%02d:%02d",
                                                    prefs.quietHoursStartHour,
                                                    prefs.quietHoursStartMinute
                                                ),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Toque para alterar",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Card Término
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onOpenEndTimePicker() }
                                        .testTag("quiet_hours_end_time_picker"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Término do Repouso",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Bedtime,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = String.format(
                                                    Locale.getDefault(),
                                                    "%02d:%02d",
                                                    prefs.quietHoursEndHour,
                                                    prefs.quietHoursEndMinute
                                                ),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Toque para alterar",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }

                            // Presets Rápidos
                            Text(
                                text = "Predefinições Rápidas",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = true,
                                            startHour = 22,
                                            startMinute = 0,
                                            endHour = 7,
                                            endMinute = 0,
                                            daysCsv = prefs.quietHoursDaysCsv,
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("preset_night"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Noite (22h-7h)", style = MaterialTheme.typography.labelSmall)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = true,
                                            startHour = 0,
                                            startMinute = 0,
                                            endHour = 8,
                                            endMinute = 0,
                                            daysCsv = prefs.quietHoursDaysCsv,
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("preset_dawn"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Madrugada (0h-8h)", style = MaterialTheme.typography.labelSmall)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = true,
                                            startHour = 12,
                                            startMinute = 0,
                                            endHour = 13,
                                            endMinute = 30,
                                            daysCsv = prefs.quietHoursDaysCsv,
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("preset_lunch"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Almoço (12h-13h30)", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            HorizontalDivider()

                            // Dias da Semana Ativos
                            Text(
                                text = "Dias em que o Não Perturbe se Aplica",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )

                            val selectedDays = remember(prefs.quietHoursDaysCsv) {
                                ScheduleCalculator.parseDaysOfWeek(prefs.quietHoursDaysCsv)
                            }

                            val daysMap = listOf(
                                DayOfWeek.MONDAY to "Seg",
                                DayOfWeek.TUESDAY to "Ter",
                                DayOfWeek.WEDNESDAY to "Qua",
                                DayOfWeek.THURSDAY to "Qui",
                                DayOfWeek.FRIDAY to "Sex",
                                DayOfWeek.SATURDAY to "Sáb",
                                DayOfWeek.SUNDAY to "Dom"
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                daysMap.forEach { (day, label) ->
                                    val isSelected = selectedDays.contains(day)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            val newDays = if (isSelected) {
                                                if (selectedDays.size > 1) selectedDays - day else selectedDays
                                            } else {
                                                selectedDays + day
                                            }
                                            val newCsv = newDays.map { it.value }.sorted().joinToString(",")
                                            viewModel.updateQuietHours(
                                                enabled = prefs.quietHoursEnabled,
                                                startHour = prefs.quietHoursStartHour,
                                                startMinute = prefs.quietHoursStartMinute,
                                                endHour = prefs.quietHoursEndHour,
                                                endMinute = prefs.quietHoursEndMinute,
                                                daysCsv = newCsv,
                                                notifySilently = prefs.quietHoursNotifySilently
                                            )
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("day_chip_${day.name}")
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = prefs.quietHoursEnabled,
                                            startHour = prefs.quietHoursStartHour,
                                            startMinute = prefs.quietHoursStartMinute,
                                            endHour = prefs.quietHoursEndHour,
                                            endMinute = prefs.quietHoursEndMinute,
                                            daysCsv = "1,2,3,4,5,6,7",
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    }
                                ) {
                                    Text("Todos os dias")
                                }

                                TextButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = prefs.quietHoursEnabled,
                                            startHour = prefs.quietHoursStartHour,
                                            startMinute = prefs.quietHoursStartMinute,
                                            endHour = prefs.quietHoursEndHour,
                                            endMinute = prefs.quietHoursEndMinute,
                                            daysCsv = "1,2,3,4,5",
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    }
                                ) {
                                    Text("Seg a Sex")
                                }

                                TextButton(
                                    onClick = {
                                        viewModel.updateQuietHours(
                                            enabled = prefs.quietHoursEnabled,
                                            startHour = prefs.quietHoursStartHour,
                                            startMinute = prefs.quietHoursStartMinute,
                                            endHour = prefs.quietHoursEndHour,
                                            endMinute = prefs.quietHoursEndMinute,
                                            daysCsv = "6,7",
                                            notifySilently = prefs.quietHoursNotifySilently
                                        )
                                    }
                                ) {
                                    Text("Fins de semana")
                                }
                            }

                            HorizontalDivider()

                            // Notificação silenciosa
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Notificação silenciosa de aviso", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Informa discretamente no painel do Android que o lembrete foi pausado para descanso",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.quietHoursNotifySilently,
                                    onCheckedChange = { checked ->
                                        viewModel.updateQuietHours(
                                            enabled = prefs.quietHoursEnabled,
                                            startHour = prefs.quietHoursStartHour,
                                            startMinute = prefs.quietHoursStartMinute,
                                            endHour = prefs.quietHoursEndHour,
                                            endMinute = prefs.quietHoursEndMinute,
                                            daysCsv = prefs.quietHoursDaysCsv,
                                            notifySilently = checked
                                        )
                                    },
                                    modifier = Modifier.testTag("toggle_silent_notification")
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Integração com Android WorkManager
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workmanager_integration_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Integração com Android WorkManager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "O WorkManager é o mecanismo nativo do Android que executa as tarefas periódicas em segundo plano. Sempre que um disparo ocorre, o WorkManager consulta as configurações de descanso acima e o banco Room antes de reproduzir áudio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Sincronizado com WorkManager",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Intervalo Mínimo: 15 min",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            ReminderWorkScheduler.enqueueImmediateTest(context)
                            Toast.makeText(
                                context,
                                "Worker do WorkManager enfileirado! As regras de descanso serão avaliadas.",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_workmanager_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disparar Verificação do WorkManager Agora")
                    }
                }
            }
        }

        // 4. Períodos de Descanso dos Perfis Salvos no Room
        item {
            val profilesWithRests = uiState.profiles.filter { it.restingPeriods.isNotEmpty() }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("room_resting_periods_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Descansos por Perfil (Banco Room)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Além do horário geral de Não Perturbe, cada perfil no banco de dados Room pode ter janelas específicas de descanso (ex: almoço, reuniões ou turnos).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (profilesWithRests.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Nenhum descanso específico cadastrado nos perfis. O agendamento segue o Não Perturbe geral.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profilesWithRests.forEach { profile ->
                                profile.restingPeriods.forEach { rest ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = rest.label.ifBlank { "Descanso" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Perfil: ${profile.name}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = String.format(
                                                        Locale.getDefault(),
                                                        "%02d:%02d - %02d:%02d",
                                                        rest.startTime.hour,
                                                        rest.startTime.minute,
                                                        rest.endTime.hour,
                                                        rest.endTime.minute
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Conteúdo de configurações de volume, notificações, bateria e backup.
 */
@Composable
fun GeneralSettingsContent(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onOpenBatteryDialog: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val prefs = uiState.preferences

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Volume e Áudio
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Volume e Áudio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    // Master Volume
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Volume Geral do App")
                            Text("${prefs.masterVolumePercent}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = prefs.masterVolumePercent.toFloat(),
                            onValueChange = { viewModel.updatePreferences(masterVolume = it.toInt()) },
                            valueRange = 0f..100f
                        )
                    }

                    // Volume Source
                    Column {
                        Text("Fonte de Volume", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !prefs.useAppVolumeOnly,
                                onClick = { viewModel.updatePreferences(useAppVolumeOnly = false) }
                            )
                            Text("Usar volume de mídia do sistema", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = prefs.useAppVolumeOnly,
                                onClick = { viewModel.updatePreferences(useAppVolumeOnly = true) }
                            )
                            Text("Usar volume próprio do app (independente)", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    FilledTonalButton(
                        onClick = { viewModel.testLoudSpeaker(prefs.masterVolumePercent) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_loudspeaker_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testar Som no Alto-falante do Celular")
                    }

                    HorizontalDivider()

                    // Fade-in
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Fade-in suave", fontWeight = FontWeight.SemiBold)
                            Text("Inicia o áudio baixo e aumenta suavemente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefs.fadeInEnabled,
                            onCheckedChange = { viewModel.updatePreferences(fadeInEnabled = it) }
                        )
                    }

                    if (prefs.fadeInEnabled) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Duração do Fade-in", style = MaterialTheme.typography.bodySmall)
                                Text(String.format(Locale.getDefault(), "%.1f s", prefs.fadeInDurationSeconds), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(
                                value = prefs.fadeInDurationSeconds,
                                onValueChange = { viewModel.updatePreferences(fadeInDuration = it) },
                                valueRange = 0.5f..10.0f
                            )
                        }
                    }
                }
            }
        }

        // 2. Comportamento e Notificações
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Comportamento e Notificações", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    // Vibrate
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Vibrar junto com o áudio", fontWeight = FontWeight.SemiBold)
                            Text("Gera pulso táctil no momento do disparo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefs.vibrateWithAudio,
                            onCheckedChange = { viewModel.updatePreferences(vibrate = it) }
                        )
                    }

                    HorizontalDivider()

                    // Show Label in Notification
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Exibir rótulo do áudio", fontWeight = FontWeight.SemiBold)
                            Text("Mostra o texto do lembrete na notificação ao tocar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefs.showLabelInNotification,
                            onCheckedChange = { viewModel.updatePreferences(showLabelInNotif = it) }
                        )
                    }

                    HorizontalDivider()

                    // Respect Do Not Disturb
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Respeitar Não Perturbe do Sistema", fontWeight = FontWeight.SemiBold)
                            Text("Pula o disparo e envia aviso discreto se o Não perturbe do Android estiver ativo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefs.respectDoNotDisturb,
                            onCheckedChange = { viewModel.updatePreferences(respectDnd = it) }
                        )
                    }
                }
            }
        }

        // 3. Assistente de Bateria
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Assistente de Bateria", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Para que os lembretes toquem com precisão com o aparelho bloqueado, o aplicativo precisa ter a otimização de bateria desativada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (uiState.isBatteryOptimizationIgnored) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (uiState.isBatteryOptimizationIgnored) Color(0xFF43A047) else Color(0xFFFB8C00),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isBatteryOptimizationIgnored) "Otimização ignorada (Ideal)" else "Otimização ativa (Pode atrasar)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(onClick = onOpenBatteryDialog) {
                            Text("Instruções")
                        }
                    }
                }
            }
        }

        // 4. Backup e Exportação
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup e Exportação", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Exporte todos os perfis, descansos e configurações para um arquivo JSON ou restaure em outro aparelho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportBackup,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_backup_button")
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exportar")
                        }

                        Button(
                            onClick = onImportBackup,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_backup_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restaurar")
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun BatteryAssistantDialog(
    isIgnored: Boolean,
    onDismiss: () -> Unit,
    onRequestIgnore: () -> Unit
) {
    val context = LocalContext.current
    var isCurrentlyIgnored by remember {
        mutableStateOf(DeviceStateHelper.isIgnoringBatteryOptimizations(context))
    }
    var selectedGuideCategory by remember { mutableIntStateOf(0) }

    fun refreshStatus() {
        isCurrentlyIgnored = DeviceStateHelper.isIgnoringBatteryOptimizations(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("battery_assistant_dialog"),
        icon = {
            Icon(
                imageVector = if (isCurrentlyIgnored) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = if (isCurrentlyIgnored) Color(0xFF2E7D32) else Color(0xFFE65100),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Otimização de Bateria e Lembretes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Live Detection Card
                item {
                    val statusBgColor = if (isCurrentlyIgnored) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    val statusTextColor = if (isCurrentlyIgnored) Color(0xFF1B5E20) else Color(0xFFE65100)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("battery_status_indicator"),
                        colors = CardDefaults.cardColors(containerColor = statusBgColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (isCurrentlyIgnored) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = statusTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isCurrentlyIgnored) "Status: Otimização Desativada (Ideal)" else "Status: Otimização Ativa (Restrito)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = statusTextColor
                                    )
                                }
                                TextButton(
                                    onClick = { refreshStatus() },
                                    modifier = Modifier.testTag("btn_refresh_battery_status")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Verificar", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verificar", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Text(
                                text = if (isCurrentlyIgnored) {
                                    "Excelente! O aplicativo está na lista de permissões (whitelist) do Android. Os lembretes e áudios tocarão pontualmente mesmo com o aparelho bloqueado."
                                } else {
                                    "Atenção: A otimização de bateria do sistema está ATIVA para este app. O modo Doze do Android pode atrasar ou silenciar lembretes quando a tela estiver desligada."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = statusTextColor
                            )
                        }
                    }
                }

                // 2. Action Shortcuts
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onRequestIgnore()
                                refreshStatus()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_request_battery_whitelist")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Solicitar Isenção Direta")
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_open_app_details")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Abrir Configurações do App")
                        }
                    }
                }

                // 3. Step-by-step Guide Selector
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Passo a Passo para Whitelist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Selecione o modelo do seu dispositivo para ver o guia detalhado:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedGuideCategory == 0,
                            onClick = { selectedGuideCategory = 0 },
                            label = { Text("Padrão / Pixel / Moto") },
                            leadingIcon = {
                                if (selectedGuideCategory == 0) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                        FilterChip(
                            selected = selectedGuideCategory == 1,
                            onClick = { selectedGuideCategory = 1 },
                            label = { Text("Samsung (One UI)") },
                            leadingIcon = {
                                if (selectedGuideCategory == 1) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                        FilterChip(
                            selected = selectedGuideCategory == 2,
                            onClick = { selectedGuideCategory = 2 },
                            label = { Text("Xiaomi / Redmi (MIUI)") },
                            leadingIcon = {
                                if (selectedGuideCategory == 2) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                        FilterChip(
                            selected = selectedGuideCategory == 3,
                            onClick = { selectedGuideCategory = 3 },
                            label = { Text("Outros Fabricantes") },
                            leadingIcon = {
                                if (selectedGuideCategory == 3) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                // 4. Instructions Card based on Selected Tab
                item {
                    when (selectedGuideCategory) {
                        0 -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "📱 Android Padrão (Pixel, Motorola, etc.)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "1. Toque no botão 'Solicitar Isenção Direta' acima e selecione 'Permitir' na caixa de diálogo do sistema.\n\n" +
                                                "2. Caso o pop-up não apareça, toque em 'Abrir Configurações do App'.\n\n" +
                                                "3. Toque em 'Bateria' (ou 'Uso de bateria do aplicativo').\n\n" +
                                                "4. Selecione a opção 'Sem restrições' (Unrestricted) em vez de 'Otimizado'.\n\n" +
                                                "5. Retorne ao app e toque em 'Verificar' para confirmar o status liberado.",
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35
                                    )
                                }
                            }
                        }
                        1 -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "📱 Samsung Galaxy (One UI)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "1. Toque em 'Abrir Configurações do App' > toque em 'Bateria' e escolha 'Sem restrições'.\n\n" +
                                                "2. Abra as Configurações do aparelho > Bateria > 'Limites de uso em segundo plano'.\n\n" +
                                                "3. Toque em 'Aplicativos que nunca são suspensos' > toque no botão '+' no topo e adicione o 'Lembretes em Áudio'.\n\n" +
                                                "4. Verifique também se o app não está marcado na lista de 'Aplicativos em suspensão profunda'.",
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35
                                    )
                                }
                            }
                        }
                        2 -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "📱 Xiaomi, Redmi & POCO (MIUI / HyperOS)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "1. Toque em 'Abrir Configurações do App' e ative a chave 'Início Automático' (Autostart).\n\n" +
                                                "2. Na mesma tela, toque em 'Economia de bateria' e selecione 'Nenhuma restrição' (Sem restrições).\n\n" +
                                                "3. Abra a tela de apps recentes (multitarefa), mantenha o dedo pressionado no app e toque no ícone de Cadeado para travá-lo na RAM.",
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35
                                    )
                                }
                            }
                        }
                        3 -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "📱 Huawei, Realme, OPPO, Vivo, ASUS",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "1. Vá em Configurações > Aplicativos > Lembretes em Áudio.\n\n" +
                                                "2. Em 'Inicialização de apps' ou 'Gerenciador de Inicialização', desative o modo automático e ative 'Permitir execução em segundo plano' e 'Início automático'.\n\n" +
                                                "3. Em 'Otimização de Bateria', marque a opção 'Não permitir otimização'.",
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Additional System Note
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Dica: No Android 12 ou superior, verifique também se a permissão 'Alarmes e lembretes' está habilitada para permitir disparos pontuais no horário exato.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_close_battery_dialog")
            ) {
                Text("Entendi")
            }
        }
    )
}

