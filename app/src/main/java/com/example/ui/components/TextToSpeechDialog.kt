package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.AudioType
import com.example.util.TextToSpeechHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TextToSpeechDialog(
    initialText: String = "",
    initialLabel: String = "",
    onDismiss: () -> Unit,
    onSave: (uriString: String, label: String, text: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsHelper = remember { TextToSpeechHelper(context) }

    var text by remember { mutableStateOf(initialText) }
    var label by remember { mutableStateOf(initialLabel) }
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var pitch by remember { mutableFloatStateOf(1.0f) }

    var isSpeakingPreview by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            ttsHelper.stop()
            ttsHelper.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) {
                ttsHelper.stop()
                onDismiss()
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Texto para Voz (TTS)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Digite o texto que a voz do Android falará durante o lembrete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Text field
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (label.isBlank() || label == "Lembrete Falado") {
                            // Suggest short label from first words
                            val words = it.trim().split(" ").take(4).joinToString(" ")
                            if (words.isNotBlank()) {
                                label = words.take(30)
                            }
                        }
                    },
                    label = { Text("Texto da mensagem falada") },
                    placeholder = { Text("Ex: Beba um copo d'água e mantenha a postura.") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_text_input")
                )

                // Label field
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Rótulo do lembrete") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_label_input")
                )

                // Speech Rate slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Velocidade da voz", style = MaterialTheme.typography.bodySmall)
                        Text(String.format("%.1fx", speechRate), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = speechRate,
                        onValueChange = { speechRate = it },
                        valueRange = 0.7f..1.5f,
                        steps = 7
                    )
                }

                // Preview voice button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isSpeakingPreview) {
                                ttsHelper.stop()
                                isSpeakingPreview = false
                            } else {
                                if (text.isNotBlank()) {
                                    isSpeakingPreview = true
                                    ttsHelper.speak(
                                        text = text,
                                        speechRate = speechRate,
                                        pitch = pitch,
                                        onDone = { isSpeakingPreview = false }
                                    )
                                } else {
                                    errorMessage = "Digite um texto para testar a voz."
                                }
                            }
                        },
                        enabled = text.isNotBlank() && !isSaving,
                        modifier = Modifier.testTag("test_tts_voice_button")
                    ) {
                        Icon(
                            imageVector = if (isSpeakingPreview) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSpeakingPreview) "Parar voz" else "Ouvir voz")
                    }
                }

                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isSaving) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gerando áudio...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isBlank()) {
                        errorMessage = "Digite um texto para o lembrete."
                        return@Button
                    }

                    isSaving = true
                    errorMessage = null
                    ttsHelper.stop()
                    isSpeakingPreview = false

                    scope.launch(Dispatchers.IO) {
                        val dir = File(context.filesDir, "tts").apply { mkdirs() }
                        val outputFile = File(dir, "tts_${System.currentTimeMillis()}.wav")

                        ttsHelper.synthesizeToFile(
                            text = text,
                            outputFile = outputFile,
                            speechRate = speechRate,
                            pitch = pitch
                        ) { success ->
                            scope.launch(Dispatchers.Main) {
                                isSaving = false
                                if (success) {
                                    val uri = Uri.fromFile(outputFile).toString()
                                    onSave(uri, label.ifBlank { "Lembrete Falado" }, text)
                                } else {
                                    errorMessage = "Não foi possível sintetizar a voz. Tente novamente."
                                }
                            }
                        }
                    }
                },
                enabled = text.isNotBlank() && !isSaving,
                modifier = Modifier.testTag("save_tts_button")
            ) {
                Text("Salvar na biblioteca")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    ttsHelper.stop()
                    onDismiss()
                },
                enabled = !isSaving
            ) {
                Text("Cancelar")
            }
        }
    )
}
