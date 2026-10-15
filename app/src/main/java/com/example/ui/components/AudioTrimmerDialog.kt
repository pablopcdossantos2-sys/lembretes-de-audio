package com.example.ui.components

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AudioItem
import com.example.util.AudioTrimmerUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

@Composable
fun AudioTrimmerDialog(
    audio: AudioItem,
    onDismiss: () -> Unit,
    onSaveTrimmed: (startMs: Long, endMs: Long, replaceOriginal: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isAnalyzing by remember { mutableStateOf(true) }
    var audioInfo by remember { mutableStateOf<AudioTrimmerUtil.AudioFileInfo?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }

    // Start and End trim positions in milliseconds
    var startTrimMs by remember { mutableLongStateOf(0L) }
    var endTrimMs by remember { mutableLongStateOf(0L) }

    var replaceOriginal by remember { mutableStateOf(true) }
    var isTrimming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Preview playback state
    var isPlayingTrimmed by remember { mutableStateOf(false) }
    var previewPlayheadMs by remember { mutableLongStateOf(0L) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackProgressJob by remember { mutableStateOf<Job?>(null) }

    // Load and analyze the audio waveform on dialog open
    LaunchedEffect(audio.uriString) {
        isAnalyzing = true
        errorMessage = null
        try {
            val info = AudioTrimmerUtil.analyzeAudioFile(context, audio.uriString)
            audioInfo = info
            durationMs = info.durationMs
            startTrimMs = 0L
            endTrimMs = info.durationMs
        } catch (e: Exception) {
            errorMessage = "Falha ao analisar o arquivo de áudio: ${e.localizedMessage}"
        } finally {
            isAnalyzing = false
        }
    }

    // Stop and release preview player on dismiss
    fun stopPreview() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlayingTrimmed = false
        previewPlayheadMs = startTrimMs
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreview()
        }
    }

    fun playTrimmedSegment() {
        if (isPlayingTrimmed) {
            stopPreview()
            return
        }

        try {
            stopPreview()
            val uri = Uri.parse(audio.uriString)
            val file = AudioTrimmerUtil.resolveFileFromUri(context, uri)

            val player = MediaPlayer().apply {
                if (file != null && file.exists()) {
                    setDataSource(file.absolutePath)
                } else {
                    setDataSource(context, uri)
                }
                prepare()
            }

            mediaPlayer = player
            player.seekTo(startTrimMs.toInt())
            player.start()
            isPlayingTrimmed = true
            previewPlayheadMs = startTrimMs

            playbackProgressJob = scope.launch {
                val targetEnd = endTrimMs
                while (isActive && isPlayingTrimmed) {
                    val currentPos = try { player.currentPosition.toLong() } catch (_: Exception) { targetEnd }
                    previewPlayheadMs = currentPos

                    if (currentPos >= targetEnd || !player.isPlaying) {
                        stopPreview()
                        break
                    }
                    delay(40)
                }
            }
        } catch (e: Exception) {
            errorMessage = "Erro ao reproduzir prévia: ${e.localizedMessage}"
            stopPreview()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isTrimming) {
                stopPreview()
                onDismiss()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_trimmer_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Aparar Áudio / Silêncio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = audio.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isAnalyzing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            text = "Analisando forma de onda e silêncio...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val info = audioInfo
                    val totalDuration = durationMs.coerceAtLeast(100L)
                    val trimmedDuration = max(0L, endTrimMs - startTrimMs)
                    val cutStartDuration = startTrimMs
                    val cutEndDuration = max(0L, totalDuration - endTrimMs)

                    // Auto-Detect Silence Banner Action
                    info?.let {
                        val hasSuggestedCuts = it.suggestedStartMs > 80L || (totalDuration - it.suggestedEndMs) > 80L
                        if (hasSuggestedCuts) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auto_detect_silence_card"),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.AutoFixHigh,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "Silêncio Detectado!",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                            val cutTotal = (it.suggestedStartMs + (totalDuration - it.suggestedEndMs)) / 1000f
                                            Text(
                                                text = String.format(java.util.Locale.US, "Remove ~%.1fs de silêncio", cutTotal),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            startTrimMs = it.suggestedStartMs
                                            endTrimMs = it.suggestedEndMs
                                            stopPreview()
                                        },
                                        modifier = Modifier.testTag("auto_detect_silence_button"),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary
                                        )
                                    ) {
                                        Text("Aplicar", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Interactive Waveform Visualizer with Cut Regions & Playhead
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .testTag("trimmer_waveform_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                val canvasWidth = size.width
                                val canvasHeight = size.height
                                val midY = canvasHeight / 2f

                                val points = info?.waveformPoints ?: FloatArray(50) { 0.2f }
                                val barCount = points.size
                                val barSpacing = 2.dp.toPx()
                                val barWidth = ((canvasWidth - (barSpacing * (barCount - 1))) / barCount).coerceAtLeast(1.5f)

                                val startFraction = (startTrimMs.toFloat() / totalDuration).coerceIn(0f, 1f)
                                val endFraction = (endTrimMs.toFloat() / totalDuration).coerceIn(0f, 1f)
                                val playheadFraction = (previewPlayheadMs.toFloat() / totalDuration).coerceIn(0f, 1f)

                                val startCutX = startFraction * canvasWidth
                                val endCutX = endFraction * canvasWidth
                                val playheadX = playheadFraction * canvasWidth

                                // Draw waveform bars
                                for (i in 0 until barCount) {
                                    val barFraction = i.toFloat() / barCount
                                    val left = i * (barWidth + barSpacing)
                                    val amp = points[i].coerceIn(0.08f, 1.0f)
                                    val barHeight = canvasHeight * 0.85f * amp
                                    val top = (canvasHeight - barHeight) / 2f

                                    val isInsideTrimmedRange = barFraction in startFraction..endFraction
                                    val color = if (isInsideTrimmedRange) {
                                        Color(0xFF38BDF8) // Vibrant sky blue
                                    } else {
                                        Color(0xFF475569) // Dimmed slate gray for trimmed silence
                                    }

                                    drawRoundRect(
                                        color = color,
                                        topLeft = Offset(left, top),
                                        size = Size(barWidth, barHeight),
                                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                                        alpha = if (isInsideTrimmedRange) 0.95f else 0.35f
                                    )
                                }

                                // Overlay shaded region for cut start
                                if (startCutX > 0f) {
                                    drawRect(
                                        color = Color(0x66EF4444),
                                        topLeft = Offset(0f, 0f),
                                        size = Size(startCutX, canvasHeight)
                                    )
                                }

                                // Overlay shaded region for cut end
                                if (endCutX < canvasWidth) {
                                    drawRect(
                                        color = Color(0x66EF4444),
                                        topLeft = Offset(endCutX, 0f),
                                        size = Size(canvasWidth - endCutX, canvasHeight)
                                    )
                                }

                                // Start cut handle line
                                drawLine(
                                    color = Color(0xFF38BDF8),
                                    start = Offset(startCutX, 0f),
                                    end = Offset(startCutX, canvasHeight),
                                    strokeWidth = 3.dp.toPx()
                                )

                                // End cut handle line
                                drawLine(
                                    color = Color(0xFF38BDF8),
                                    start = Offset(endCutX, 0f),
                                    end = Offset(endCutX, canvasHeight),
                                    strokeWidth = 3.dp.toPx()
                                )

                                // Live playback cursor line
                                if (isPlayingTrimmed) {
                                    drawLine(
                                        color = Color(0xFFFF5252),
                                        start = Offset(playheadX, 0f),
                                        end = Offset(playheadX, canvasHeight),
                                        strokeWidth = 2.5.dp.toPx()
                                    )
                                }
                            }
                        }
                    }

                    // Sliders for Start and End Trim
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Início: ${AudioTrimmerUtil.formatTimeMs(startTrimMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Fim: ${AudioTrimmerUtil.formatTimeMs(endTrimMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Material 3 Range Slider
                        val sliderRange = 0f..totalDuration.toFloat()
                        RangeSlider(
                            value = startTrimMs.toFloat()..endTrimMs.toFloat(),
                            onValueChange = { range ->
                                val newStart = range.start.toLong().coerceIn(0L, totalDuration - 200L)
                                val newEnd = range.endInclusive.toLong().coerceIn(newStart + 200L, totalDuration)
                                startTrimMs = newStart
                                endTrimMs = newEnd
                                if (isPlayingTrimmed) stopPreview()
                            },
                            valueRange = sliderRange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trimmer_range_slider")
                        )

                        // Duration Telemetry Details
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Corte início: -${AudioTrimmerUtil.formatTimeMs(cutStartDuration)}",
                                fontSize = 11.sp,
                                color = if (cutStartDuration > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Duração útil: ${AudioTrimmerUtil.formatTimeMs(trimmedDuration)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Corte fim: -${AudioTrimmerUtil.formatTimeMs(cutEndDuration)}",
                                fontSize = 11.sp,
                                color = if (cutEndDuration > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Fine-Tuning Steppers (-0.1s / +0.1s) & Reset
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                startTrimMs = max(0L, startTrimMs - 100L)
                                stopPreview()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("-0.1s Início", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                startTrimMs = min(endTrimMs - 200L, startTrimMs + 100L)
                                stopPreview()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+0.1s Início", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                startTrimMs = 0L
                                endTrimMs = totalDuration
                                stopPreview()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Restaurar", modifier = Modifier.size(16.dp))
                        }
                    }

                    // Preview Button (Audition trimmed segment)
                    Button(
                        onClick = { playTrimmedSegment() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("play_trim_preview_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlayingTrimmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlayingTrimmed) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isPlayingTrimmed) "Parar Prévia" else "Ouvir Trecho Cortado")
                    }

                    // Save Options (Replace original vs New Item)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Substituir áudio original",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (replaceOriginal) "Atualiza este lembrete existente" else "Cria um novo item na biblioteca",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = replaceOriginal,
                            onCheckedChange = { replaceOriginal = it },
                            modifier = Modifier.testTag("replace_original_switch")
                        )
                    }

                    if (isTrimming) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    errorMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    stopPreview()
                    isTrimming = true
                    onSaveTrimmed(startTrimMs, endTrimMs, replaceOriginal)
                },
                enabled = !isAnalyzing && !isTrimming && (endTrimMs > startTrimMs),
                modifier = Modifier.testTag("save_trim_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Salvar Corte")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    stopPreview()
                    onDismiss()
                },
                enabled = !isTrimming
            ) {
                Text("Cancelar")
            }
        }
    )
}
