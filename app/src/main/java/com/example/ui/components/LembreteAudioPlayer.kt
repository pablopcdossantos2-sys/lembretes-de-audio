package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.LembreteEntity
import java.util.Locale

/**
 * Componente de player de áudio dedicado para a tela de listagem de lembretes.
 * Permite ao usuário ouvir as gravações salvas diretamente no aplicativo com:
 * - Play / Pause / Stop
 * - Visualizador animado em formato de ondas sonoras (Equalizer bars)
 * - Barra de progresso interativa (Scrubbing / Slider com busca)
 * - Contador de tempo decorrido e duração total (00:00 / 00:00)
 * - Botões de salto rápido (-5s / +5s)
 * - Indicador de volume configurado
 */
@Composable
fun LembreteAudioPlayer(
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
    modifier: Modifier = Modifier
) {
    val hasAudio = !lembrete.audioUri.isNullOrBlank()

    val containerColor by animateColorAsState(
        targetValue = when {
            isPlayingThis -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            isPausedThis -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        label = "player_bg_anim"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_player_container_${lembrete.id}"),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        tonalElevation = if (isPlayingThis) 4.dp else 1.dp
    ) {
        if (!hasAudio) {
            // Estado sem gravação de áudio anexada
            EmptyAudioNotice(
                onRecordAudio = onRecordAudio,
                lembreteId = lembrete.id
            )
        } else {
            // Player completo com controles e barra de progresso
            ActiveAudioPlayerControls(
                lembrete = lembrete,
                isPlayingThis = isPlayingThis,
                isPausedThis = isPausedThis,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onPlayPause = onPlayPause,
                onStop = onStop,
                onSeekTo = onSeekTo,
                onSkip = onSkip
            )
        }
    }
}

@Composable
private fun ActiveAudioPlayerControls(
    lembrete: LembreteEntity,
    isPlayingThis: Boolean,
    isPausedThis: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSkip: (Int) -> Unit
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosition by remember { mutableFloatStateOf(0f) }

    val effectivePosition = if (isDraggingSlider) {
        sliderDragPosition.toInt()
    } else {
        currentPositionMs
    }

    val maxDuration = durationMs.coerceAtLeast(1)
    val sliderValue = effectivePosition.toFloat().coerceIn(0f, maxDuration.toFloat())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        // Cabeçalho do Player: Status da gravação e visualizador de ondas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (lembrete.audioType == "RECORDING") Icons.Default.Mic else Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (isPlayingThis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isPlayingThis -> "Reproduzindo áudio..."
                        isPausedThis -> "Reprodução em pausa"
                        else -> if (lembrete.audioType == "RECORDING") "Gravação de Voz" else "Áudio Salvo"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            // Visualizador animado de frequências quando tocando
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isPlayingThis) {
                    AudioWaveformVisualizer(isPlaying = true)
                }

                // Badge de Volume do Lembrete
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${lembrete.volumePercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Barra de progresso interativa (Slider de reprodução)
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                isDraggingSlider = true
                sliderDragPosition = newValue
            },
            onValueChangeFinished = {
                onSeekTo(sliderDragPosition.toInt())
                isDraggingSlider = false
            },
            valueRange = 0f..maxDuration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("audio_slider_${lembrete.id}")
        )

        // Linha de tempo: Tempo decorrido e Duração total
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDurationMs(effectivePosition),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Linha de botões de controle de reprodução
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Retroceder 5 segundos (-5s)
            IconButton(
                onClick = { onSkip(-5) },
                enabled = isPlayingThis || isPausedThis,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("skip_backward_button_${lembrete.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "Retroceder 5 segundos",
                    tint = if (isPlayingThis || isPausedThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Botão Principal: Play / Pause
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("play_pause_button_${lembrete.id}"),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlayingThis) "Pausar áudio" else "Ouvir gravação",
                    modifier = Modifier.size(30.dp)
                )
            }

            // Avançar 5 segundos (+5s)
            IconButton(
                onClick = { onSkip(5) },
                enabled = isPlayingThis || isPausedThis,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("skip_forward_button_${lembrete.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Avançar 5 segundos",
                    tint = if (isPlayingThis || isPausedThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Botão Parar (Stop)
            IconButton(
                onClick = onStop,
                enabled = isPlayingThis || isPausedThis,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("stop_audio_button_${lembrete.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Parar reprodução",
                    tint = if (isPlayingThis || isPausedThis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Reiniciar do início (Replay)
            IconButton(
                onClick = { onSeekTo(0) },
                enabled = isPlayingThis || isPausedThis,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("replay_audio_button_${lembrete.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = "Reiniciar do início",
                    tint = if (isPlayingThis || isPausedThis) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Visualizador animado composto por 5 barras verticais pulsantes
 * que representam o espectro de áudio durante a reprodução.
 */
@Composable
fun AudioWaveformVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "waveform_anim")

    val h1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val h2 by transition.animateFloat(
        initialValue = 16f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val h3 by transition.animateFloat(
        initialValue = 8f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val h4 by transition.animateFloat(
        initialValue = 18f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(310, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    val h5 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(390, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar5"
    )

    val bars = if (isPlaying) listOf(h1, h2, h3, h4, h5) else listOf(6f, 10f, 14f, 10f, 6f)

    Row(
        modifier = modifier
            .height(24.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun EmptyAudioNotice(
    onRecordAudio: () -> Unit,
    lembreteId: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Sem gravação de áudio",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Grave sua voz para reproduzir neste lembrete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }

        Button(
            onClick = onRecordAudio,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("record_audio_for_lembrete_$lembreteId")
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Gravar", fontSize = 12.sp)
        }
    }
}

/**
 * Formata milissegundos para o formato de tempo "mm:ss".
 */
fun formatDurationMs(millis: Int): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
