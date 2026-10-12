package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.player.AudioPlayerManager
import com.example.util.AudioRecorderHelper
import kotlinx.coroutines.delay
import java.io.File

import androidx.compose.material3.FilterChip

@Composable
fun AudioRecordDialog(
    onDismiss: () -> Unit,
    onAudioSaved: (uriString: String, label: String, file: File, periodicityMinutes: Int) -> Unit
) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }

    // Launcher for RECORD_AUDIO permission request in Compose
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordPermission = isGranted
        if (!isGranted) {
            permissionDeniedOnce = true
        }
    }

    if (!hasRecordPermission) {
        // Permission Request Dialog
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Microfone",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Permissão de Gravação de Áudio",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "O aplicativo precisa de acesso ao microfone para que você possa gravar seus próprios lembretes, metas e afirmações diretamente pelo celular.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    if (permissionDeniedOnce) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Permissão negada. É necessário ativá-la nas configurações do aplicativo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (permissionDeniedOnce) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.testTag("open_app_settings_permission_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Abrir Configurações")
                    }
                } else {
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.testTag("request_record_audio_permission_button")
                    ) {
                        Text("Conceder Permissão")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        )
    } else {
        // Active Recording UI Dialog
        RecordingActiveDialog(
            context = context,
            onDismiss = onDismiss,
            onSave = onAudioSaved
        )
    }
}

@Composable
private fun RecordingActiveDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (uriString: String, label: String, file: File, periodicityMinutes: Int) -> Unit
) {
    val recorder = remember { AudioRecorderHelper(context) }
    val previewPlayer = remember { AudioPlayerManager(context) }

    var isRecording by remember { mutableStateOf(false) }
    var secondsElapsed by remember { mutableIntStateOf(0) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("Gravação própria") }
    var periodicityMinutes by remember { mutableIntStateOf(15) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pulsing animation during recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Timer effect
    LaunchedEffect(isRecording) {
        if (isRecording) {
            secondsElapsed = 0
            while (isRecording) {
                delay(1000L)
                secondsElapsed++
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
            previewPlayer.stop()
        }
    }

    val formatSeconds = { s: Int ->
        val m = s / 60
        val sec = s % 60
        String.format("%02d:%02d", m, sec)
    }

    AlertDialog(
        onDismissRequest = {
            if (isRecording) {
                recorder.stopRecording()
            }
            previewPlayer.stop()
            onDismiss()
        },
        title = {
            Text(
                text = if (isRecording) "Gravando Áudio..." else if (recordedFile != null) "Gravação Concluída" else "Gravar Lembrete em Áudio",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Record Button or Wave Animation
                Box(contentAlignment = Alignment.Center) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .scale(pulseScale)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), CircleShape)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!isRecording) {
                                // Start recording
                                previewPlayer.stop()
                                isPreviewPlaying = false
                                val dir = File(context.filesDir, "recordings").apply { mkdirs() }
                                val file = File(dir, "gravacao_${System.currentTimeMillis()}.m4a")
                                val started = recorder.startRecording(file)
                                if (started) {
                                    recordedFile = file
                                    isRecording = true
                                    errorMessage = null
                                } else {
                                    errorMessage = "Não foi possível iniciar a gravação."
                                }
                            } else {
                                // Stop recording
                                val stopped = recorder.stopRecording()
                                isRecording = false
                                if (!stopped) {
                                    errorMessage = "Erro ao finalizar a gravação."
                                }
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                            .testTag("record_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Parar gravação" else "Iniciar gravação",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Timer & instructions
                Text(
                    text = if (isRecording) formatSeconds(secondsElapsed) else if (recordedFile != null) "Duração: ${formatSeconds(secondsElapsed)}" else "Toque no microfone para gravar",
                    style = if (isRecording) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )

                // Preview player if recorded
                AnimatedVisibility(visible = recordedFile != null && !isRecording) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    recordedFile?.let { file ->
                                        if (isPreviewPlaying) {
                                            previewPlayer.stop()
                                            isPreviewPlaying = false
                                        } else {
                                            val uri = Uri.fromFile(file).toString()
                                            previewPlayer.playAudio(
                                                uriString = uri,
                                                targetVolume = 1.0f,
                                                fadeInDurationMs = 0L,
                                                onComplete = { isPreviewPlaying = false }
                                            )
                                            isPreviewPlaying = true
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("preview_recorded_audio_button")
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPreviewPlaying) "Parar prévia" else "Ouvir gravação")
                            }
                        }

                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Nome do Lembrete") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Periodicidade: $periodicityMinutes min",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(5, 10, 15, 30, 60).forEach { mins ->
                                    FilterChip(
                                        selected = periodicityMinutes == mins,
                                        onClick = { periodicityMinutes = mins },
                                        label = { Text("$mins m", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }

                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (recordedFile != null && !isRecording) {
                Button(
                    onClick = {
                        recordedFile?.let { file ->
                            previewPlayer.stop()
                            val uriString = Uri.fromFile(file).toString()
                            onSave(uriString, label.ifBlank { "Gravação própria" }, file, periodicityMinutes)
                        }
                    },
                    modifier = Modifier.testTag("save_recorded_audio_button")
                ) {
                    Text("Salvar na biblioteca")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isRecording) recorder.stopRecording()
                    previewPlayer.stop()
                    onDismiss()
                }
            ) {
                Text("Cancelar")
            }
        }
    )
}
