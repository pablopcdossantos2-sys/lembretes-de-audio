package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.util.AudioDiagnosticLogger
import com.example.util.DiagnosticCategory
import com.example.util.DiagnosticLevel
import com.example.util.DiagnosticLogEntry
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Headphones
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
import androidx.compose.material3.ButtonDefaults
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
    var showExactAlarmDialog by remember { mutableStateOf(false) }
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
        // Alerta fixo caso a permissão de alarme exato não esteja concedida no Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !uiState.canScheduleExactAlarms) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("exact_alarm_warning_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Permissão de Alarme Exato Necessária",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "No Android 12+, autorize 'Alarmes e lembretes' para disparos pontuais.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { showExactAlarmDialog = true },
                            modifier = Modifier.testTag("btn_banner_exact_alarm_info")
                        ) {
                            Text("Ajuda", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Button(
                            onClick = { viewModel.openExactAlarmSettings() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("request_exact_alarm_permission_button")
                        ) {
                            Text("Permitir")
                        }
                    }
                }
            }
        }

        // Abas principais de navegação nas configurações
        TabRow(
            selectedTabIndex = selectedSettingsTab,
            modifier = Modifier.testTag("settings_tab_row")
        ) {
            Tab(
                selected = selectedSettingsTab == 0,
                onClick = { selectedSettingsTab = 0 },
                text = { Text("Descanso") },
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
            Tab(
                selected = selectedSettingsTab == 2,
                onClick = { selectedSettingsTab = 2 },
                text = { Text("Diagnósticos") },
                icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                modifier = Modifier.testTag("tab_diagnostics")
            )
        }

        when (selectedSettingsTab) {
            0 -> {
                // ==========================================
                // ABA 0: HORÁRIOS DE DESCANSO & NÃO PERTURBE
                // ==========================================
                QuietHoursSettingsContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenStartTimePicker = { showStartTimePicker = true },
                    onOpenEndTimePicker = { showEndTimePicker = true }
                )
            }
            1 -> {
                // ==========================================
                // ABA 1: ÁUDIO, BATERIA E BACKUP GERAL
                // ==========================================
                GeneralSettingsContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenBatteryDialog = { showBatteryAssistantDialog = true },
                    onOpenExactAlarmDialog = { showExactAlarmDialog = true },
                    onExportBackup = { exportLauncher.launch("lembretes_audio_backup.json") },
                    onImportBackup = { importLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }
            else -> {
                // ==========================================
                // ABA 2: DIAGNÓSTICOS DE ÁUDIO E SERVIÇO
                // ==========================================
                DiagnosticsSettingsContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenBatteryDialog = { showBatteryAssistantDialog = true },
                    onOpenExactAlarmDialog = { showExactAlarmDialog = true }
                )
            }
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

    // Exact Alarm Permission Dialog (Android 12+)
    if (showExactAlarmDialog) {
        ExactAlarmPermissionDialog(
            canScheduleExact = uiState.canScheduleExactAlarms,
            onDismiss = { showExactAlarmDialog = false },
            onRequestPermission = { viewModel.openExactAlarmSettings() }
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
    onOpenExactAlarmDialog: () -> Unit,
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

        // 4. Permissão de Alarmes Exatos (Android 12+)
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("exact_alarm_settings_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Permissão de Alarmes Exatos (SCHEDULE_EXACT_ALARM)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "No Android 12+ (API 31+), o sistema exige autorização expressa do usuário para disparar alarmes no minuto e segundo exatos programados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("exact_alarm_status_indicator")
                        ) {
                            Icon(
                                imageVector = if (uiState.canScheduleExactAlarms) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (uiState.canScheduleExactAlarms) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.canScheduleExactAlarms) "Autorizado (Disparos pontuais)" else "Não concedido (Pode atrasar)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.canScheduleExactAlarms) Color(0xFF1B5E20) else Color(0xFFC62828)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = onOpenExactAlarmDialog,
                                modifier = Modifier.testTag("exact_alarm_help_button")
                            ) {
                                Text("Ajuda")
                            }
                            Button(
                                onClick = { viewModel.openExactAlarmSettings() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.canScheduleExactAlarms) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.testTag("request_exact_alarm_permission_button")
                            ) {
                                Text(if (uiState.canScheduleExactAlarms) "Verificar" else "Conceder")
                            }
                        }
                    }
                }
            }
        }

        // 5. Backup e Exportação
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

/**
 * Diálogo de permissão para SCHEDULE_EXACT_ALARM no Android 12+ (API 31+).
 * Explica o motivo e orienta o usuário a habilitar 'Alarmes e lembretes'.
 */
@Composable
fun ExactAlarmPermissionDialog(
    canScheduleExact: Boolean,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("exact_alarm_permission_dialog"),
        icon = {
            Icon(
                imageVector = if (canScheduleExact) Icons.Default.CheckCircle else Icons.Default.Schedule,
                contentDescription = null,
                tint = if (canScheduleExact) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Permissão de Alarmes Exatos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (canScheduleExact) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (canScheduleExact) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (canScheduleExact) Color(0xFF1B5E20) else Color(0xFFC62828),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (canScheduleExact) "Permissão Concedida" else "Ação Recomendada",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (canScheduleExact) Color(0xFF1B5E20) else Color(0xFFC62828)
                            )
                            Text(
                                text = if (canScheduleExact)
                                    "O app tem permissão para disparar áudios no segundo programado."
                                else
                                    "Sem esta permissão, o sistema pode atrasar ou agrupar seus lembretes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (canScheduleExact) Color(0xFF2E7D32) else Color(0xFFB71C1C)
                            )
                        }
                    }
                }

                Text(
                    text = "A partir do Android 12 (API 31+), o sistema exige permissão explícita (SCHEDULE_EXACT_ALARM) para disparos pontuais.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Passos para ativar:\n1. Clique em 'Abrir Configurações' abaixo.\n2. Na tela do sistema, ative 'Permitir definição de alarmes e lembretes'.\n3. Retorne ao app e a verificação será atualizada automaticamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onRequestPermission()
                    onDismiss()
                },
                modifier = Modifier.testTag("btn_confirm_exact_alarm_settings")
            ) {
                Text(if (canScheduleExact) "Verificar Ajustes" else "Abrir Configurações")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_dismiss_exact_alarm_dialog")
            ) {
                Text("Fechar")
            }
        }
    )
}

@Composable
fun DiagnosticsSettingsContent(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onOpenBatteryDialog: () -> Unit,
    onOpenExactAlarmDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val logs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    var selectedCategoryFilter by remember { mutableStateOf<DiagnosticCategory?>(null) }
    var selectedLevelFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }

    val filteredLogs = remember(logs, selectedCategoryFilter, selectedLevelFilter) {
        logs.filter { entry ->
            (selectedCategoryFilter == null || entry.category == selectedCategoryFilter) &&
            (selectedLevelFilter == null || entry.level == selectedLevelFilter)
        }
    }

    val audioDeviceSummary = remember(logs) {
        try { DeviceStateHelper.getConnectedAudioDevicesDescription(context) } catch (_: Throwable) { "Alto-falante do Celular" }
    }
    val isHeadphones = remember(logs) {
        try { DeviceStateHelper.isHeadphonesConnected(context) } catch (_: Throwable) { false }
    }
    val canScheduleExact = remember(logs) {
        try { DeviceStateHelper.canScheduleExactAlarms(context) } catch (_: Throwable) { true }
    }
    val isBatteryIgnored = remember(logs) {
        try { DeviceStateHelper.isIgnoringBatteryOptimizations(context) } catch (_: Throwable) { true }
    }
    val isDndActive = remember(logs) {
        try { DeviceStateHelper.isDoNotDisturbActive(context) } catch (_: Throwable) { false }
    }
    val isCallActive = remember(logs) {
        try { DeviceStateHelper.isPhoneCallActive(context) } catch (_: Throwable) { false }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("diagnostics_settings_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Device & Audio Output Status Card
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnostics_status_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Status Operacional do Sistema",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (uiState.preferences.isAppMasterEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        ) {
                            Text(
                                text = if (uiState.preferences.isAppMasterEnabled) "App Ativo" else "App Desligado",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.preferences.isAppMasterEnabled) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Rota de áudio
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isHeadphones) Icons.Default.Headphones else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = if (isHeadphones) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saída de Áudio Atual",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isHeadphones) "Fones Conectados: $audioDeviceSummary" else "Alto-falante do Celular ($audioDeviceSummary)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    // Alarmes exatos
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (canScheduleExact) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (canScheduleExact) Color(0xFF2E7D32) else Color(0xFFED6C02),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Permissão de Alarmes Exatos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (canScheduleExact) "Autorizado (Disparos no segundo certo)" else "Aproximado (Contingência ativa)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            TextButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.testTag("btn_fix_exact_alarm")
                            ) {
                                Text("Ajustar", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    // Bateria
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isBatteryIgnored) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint = if (isBatteryIgnored) Color(0xFF2E7D32) else Color(0xFFED6C02),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Otimização de Bateria",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isBatteryIgnored) "Sem Restrições (Em execução contínua)" else "Economia ativada no sistema",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (!isBatteryIgnored) {
                            TextButton(
                                onClick = onOpenBatteryDialog,
                                modifier = Modifier.testTag("btn_open_battery_from_diagnostics")
                            ) {
                                Text("Resolver", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // Avisos adicionais se DND ou chamada
                    if (isDndActive || isCallActive) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isCallActive -> "Atenção: Chamada telefônica detectada no momento (lembretes pausados)."
                                    else -> "Atenção: Modo Não Perturbe ativo no aparelho."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // 2. Action Buttons (Testar Agora, Copiar, Limpar)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ações de Diagnóstico e Teste",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            viewModel.testTriggerReminderNow()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_test_audio_diagnostics"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testar Disparo de Áudio Agora (Gera logs em tempo real)", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val text = viewModel.getDiagnosticLogsText()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Logs de Diagnóstico", text)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Histórico de logs copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_copy_diagnostics")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copiar Logs")
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.clearDiagnosticLogs()
                                Toast.makeText(context, "Logs limpos com sucesso.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_clear_diagnostics")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Limpar")
                        }
                    }
                }
            }
        }

        // 3. Category Filter Chips
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Registros de Eventos (${filteredLogs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (selectedCategoryFilter != null || selectedLevelFilter != null) {
                        TextButton(onClick = {
                            selectedCategoryFilter = null
                            selectedLevelFilter = null
                        }) {
                            Text("Resetar Filtros", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategoryFilter == null && selectedLevelFilter == null,
                        onClick = {
                            selectedCategoryFilter = null
                            selectedLevelFilter = null
                        },
                        label = { Text("Todos") },
                        modifier = Modifier.testTag("filter_chip_all")
                    )

                    FilterChip(
                        selected = selectedCategoryFilter == DiagnosticCategory.PLAYBACK,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == DiagnosticCategory.PLAYBACK) null else DiagnosticCategory.PLAYBACK
                        },
                        label = { Text("Reprodução") },
                        modifier = Modifier.testTag("filter_chip_playback")
                    )

                    FilterChip(
                        selected = selectedCategoryFilter == DiagnosticCategory.ROUTING,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == DiagnosticCategory.ROUTING) null else DiagnosticCategory.ROUTING
                        },
                        label = { Text("Roteamento") },
                        modifier = Modifier.testTag("filter_chip_routing")
                    )

                    FilterChip(
                        selected = selectedCategoryFilter == DiagnosticCategory.ALARM,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == DiagnosticCategory.ALARM) null else DiagnosticCategory.ALARM
                        },
                        label = { Text("Alarmes") },
                        modifier = Modifier.testTag("filter_chip_alarm")
                    )

                    FilterChip(
                        selected = selectedCategoryFilter == DiagnosticCategory.SERVICE,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == DiagnosticCategory.SERVICE) null else DiagnosticCategory.SERVICE
                        },
                        label = { Text("Serviço") },
                        modifier = Modifier.testTag("filter_chip_service")
                    )

                    FilterChip(
                        selected = selectedLevelFilter == DiagnosticLevel.ERROR,
                        onClick = {
                            selectedLevelFilter = if (selectedLevelFilter == DiagnosticLevel.ERROR) null else DiagnosticLevel.ERROR
                        },
                        label = { Text("Erros") },
                        modifier = Modifier.testTag("filter_chip_errors")
                    )
                }
            }
        }

        // 4. Log Entries Feed
        if (filteredLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_diagnostics_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Nenhum evento registrado com o filtro atual.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Toque em 'Testar Disparo de Áudio Agora' acima para executar uma verificação completa e registrar o evento.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { logItem ->
                DiagnosticLogItemCard(entry = logItem)
            }
        }
    }
}

@Composable
fun DiagnosticLogItemCard(entry: DiagnosticLogEntry) {
    val levelColor = when (entry.level) {
        DiagnosticLevel.SUCCESS -> Color(0xFF2E7D32)
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.primary
        DiagnosticLevel.WARN -> Color(0xFFED6C02)
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    val levelBgColor = when (entry.level) {
        DiagnosticLevel.SUCCESS -> Color(0xFFE8F5E9)
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        DiagnosticLevel.WARN -> Color(0xFFFFF3E0)
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    }

    val levelIcon = when (entry.level) {
        DiagnosticLevel.SUCCESS -> Icons.Default.CheckCircle
        DiagnosticLevel.INFO -> Icons.Default.Info
        DiagnosticLevel.WARN -> Icons.Default.Warning
        DiagnosticLevel.ERROR -> Icons.Default.Error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic_log_item_${entry.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = levelBgColor,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = levelIcon,
                        contentDescription = null,
                        tint = levelColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = entry.category.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = entry.formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (!entry.details.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = entry.details,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

