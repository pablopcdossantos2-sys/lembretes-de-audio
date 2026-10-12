package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import com.example.worker.ReminderWorkScheduler
import android.widget.Toast
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    showBatteryDialogDefault: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBatteryAssistantDialog by remember { mutableStateOf(showBatteryDialogDefault) }
    var importResultDialog by remember { mutableStateOf<ImportResult?>(null) }

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Configurações Gerais",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Audio and Volume Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                            Text("${uiState.preferences.masterVolumePercent}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = uiState.preferences.masterVolumePercent.toFloat(),
                            onValueChange = { viewModel.updatePreferences(masterVolume = it.toInt()) },
                            valueRange = 0f..100f
                        )
                    }

                    // Volume Source
                    Column {
                        Text("Fonte de Volume", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !uiState.preferences.useAppVolumeOnly,
                                onClick = { viewModel.updatePreferences(useAppVolumeOnly = false) }
                            )
                            Text("Usar volume de mídia do sistema", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = uiState.preferences.useAppVolumeOnly,
                                onClick = { viewModel.updatePreferences(useAppVolumeOnly = true) }
                            )
                            Text("Usar volume próprio do app (independente)", style = MaterialTheme.typography.bodySmall)
                        }
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
                            checked = uiState.preferences.fadeInEnabled,
                            onCheckedChange = { viewModel.updatePreferences(fadeInEnabled = it) }
                        )
                    }

                    if (uiState.preferences.fadeInEnabled) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Duração do Fade-in", style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.1f s", uiState.preferences.fadeInDurationSeconds), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(
                                value = uiState.preferences.fadeInDurationSeconds,
                                onValueChange = { viewModel.updatePreferences(fadeInDuration = it) },
                                valueRange = 0.5f..10.0f
                            )
                        }
                    }
                }
            }
        }

        // 2. Behavior and Notifications Card
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
                            checked = uiState.preferences.vibrateWithAudio,
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
                            checked = uiState.preferences.showLabelInNotification,
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
                            Text("Respeitar Não Perturbe", fontWeight = FontWeight.SemiBold)
                            Text("Pula o disparo e envia aviso discreto se o Não perturbe estiver ativo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.preferences.respectDoNotDisturb,
                            onCheckedChange = { viewModel.updatePreferences(respectDnd = it) }
                        )
                    }
                }
            }
        }

        // 3. Battery Assistant Card
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
                        text = "Para que os lembretes toquem no segundo exato com o aparelho bloqueado, o aplicativo precisa ter a otimização de bateria desativada.",
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

                        Button(onClick = { showBatteryAssistantDialog = true }) {
                            Text("Instruções")
                        }
                    }
                }
            }
        }

        // Agendamento em Segundo Plano (WorkManager)
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workmanager_settings_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Agendamento em Segundo Plano (WorkManager)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "O Android WorkManager gerencia os disparos periódicos em segundo plano, respeitando fielmente as regras de Horário de Descanso salvas no Room e o modo Não Perturbe.",
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
                            color = if (uiState.preferences.isAppMasterEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = if (uiState.preferences.isAppMasterEnabled) "WorkManager: Ativo" else "WorkManager: Inativo",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.preferences.isAppMasterEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "Descanso Protegido no Room",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            ReminderWorkScheduler.enqueueImmediateTest(context)
                            Toast.makeText(
                                context,
                                "Worker do WorkManager enfileirado para execução em segundo plano!",
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

        // 4. Backup & Restore Card
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("lembretes_backup.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Exportar")
                        }

                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Importar")
                        }
                    }
                }
            }
        }

        // 5. About
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Lembretes em Áudio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Versão 1.0 • Desenvolvido com Jetpack Compose & Material 3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Mantenha o foco nos seus objetivos e hábitos com avisos sonoros regulares.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // Battery Assistant Dialog
    if (showBatteryAssistantDialog) {
        BatteryAssistantDialog(
            isIgnored = uiState.isBatteryOptimizationIgnored,
            onDismiss = { showBatteryAssistantDialog = false },
            onRequestIgnore = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(fallbackIntent)
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

@Composable
fun BatteryAssistantDialog(
    isIgnored: Boolean,
    onDismiss: () -> Unit,
    onRequestIgnore: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assistente de Bateria e Segundo Plano") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        text = "Sistemas Android modernos utilizam o modo Doze para economizar energia, o que pode suspender alarmes em segundo plano.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    Text(
                        text = "1. Isenção da Otimização do Android",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Toque no botão abaixo para conceder isenção direta de bateria ao app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    Text(
                        text = "2. Dicas específicas por fabricante:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "• Xiaomi (MIUI/HyperOS): Em Configurações > Apps > Lembretes em Áudio, ative 'Início Automático' e defina economia de bateria para 'Sem restrições'.\n\n• Samsung (One UI): Em Configurações > Bateria > Limites de uso em 2º plano, verifique se o app não está em 'Aplicativos em suspensão profunda'.\n\n• Motorola: Desative a otimização de bateria nas configurações do app.\n\n• Huawei: Ative 'Gerenciamento manual' no inicializador de aplicativos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onRequestIgnore()
                onDismiss()
            }) {
                Text("Desativar otimização")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}
