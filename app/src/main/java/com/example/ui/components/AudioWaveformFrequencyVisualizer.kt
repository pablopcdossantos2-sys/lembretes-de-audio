package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.RecordingStatus
import kotlin.math.sin

enum class VisualizerMode(val label: String) {
    SPECTRUM("Frequências"),
    WAVEFORM("Forma de Onda"),
    DUAL("Combinado")
}

/**
 * Modern high-fidelity real-time audio waveform and frequency spectrum visualizer.
 * Renders live frequency bands (FFT), oscilloscope waveform, decibels, and peak frequency.
 */
@Composable
fun AudioWaveformFrequencyVisualizer(
    status: RecordingStatus,
    frequencyBands: FloatArray,
    waveformPoints: FloatArray,
    decibels: Float,
    peakFrequencyHz: Float,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(VisualizerMode.SPECTRUM) }

    // Ambient floating wave animation when IDLE or between voice pauses
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_visualizer")
    val ambientPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f, // 2*PI
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient_phase"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Dynamic color theme based on recording status
    val primaryAccent by animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.RECORDING -> Color(0xFFFF5252) // Vibrant studio recording red
            RecordingStatus.PAUSED -> Color(0xFFFFB300)    // Amber warning
            RecordingStatus.FINISHED -> Color(0xFF00E676)  // Green success
            RecordingStatus.IDLE -> MaterialTheme.colorScheme.primary
        },
        label = "primary_accent"
    )

    val secondaryAccent by animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.RECORDING -> Color(0xFFFF4081) // Neon pink
            RecordingStatus.PAUSED -> Color(0xFFFF8F00)    // Deep orange
            RecordingStatus.FINISHED -> Color(0xFF00B0FF)  // Electric blue
            RecordingStatus.IDLE -> MaterialTheme.colorScheme.tertiary
        },
        label = "secondary_accent"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = if (status == RecordingStatus.RECORDING) {
                    primaryAccent.copy(alpha = glowAlpha)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
            .testTag("audio_waveform_frequency_visualizer"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Telemetry Header (Real-time dB, Peak Hz, Mode Selector)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live Status Indicator & Hz Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = primaryAccent.copy(alpha = 0.18f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            shape = CircleShape,
                            color = primaryAccent,
                            modifier = Modifier.size(10.dp)
                        ) {}
                    }
                }

                Column {
                    Text(
                        text = if (status == RecordingStatus.RECORDING) "ÁUDIO AO VIVO" else "ANALISADOR DE ESPECTRO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (peakFrequencyHz > 20f && status == RecordingStatus.RECORDING) {
                            "Pico: ${peakFrequencyHz.toInt()} Hz"
                        } else {
                            "Pico: -- Hz"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Real-Time Decibel Meter readout
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dbClamped = decibels.coerceIn(-60f, 0f)
                    val dbColor = when {
                        dbClamped > -6f -> Color(0xFFFF5252)
                        dbClamped > -18f -> Color(0xFFFFB300)
                        else -> Color(0xFF00E676)
                    }

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dbColor)
                    )

                    Text(
                        text = String.format(java.util.Locale.US, "%.1f dB", dbClamped),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Mode Selector Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VisualizerMode.entries.forEach { item ->
                val isSelected = mode == item
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) primaryAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) primaryAccent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { mode = item }
                        .testTag("visualizer_mode_${item.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (item) {
                                VisualizerMode.SPECTRUM -> Icons.Default.GraphicEq
                                VisualizerMode.WAVEFORM -> Icons.Default.ShowChart
                                VisualizerMode.DUAL -> Icons.Default.ViewStream
                            },
                            contentDescription = null,
                            tint = if (isSelected) primaryAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.label,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) primaryAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Main Real-Time Visualizer Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0F172A)) // Deep studio dark slate
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("waveform_frequency_canvas")
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val midY = canvasHeight / 2f

                // Draw subtle background frequency grid lines
                val gridColor = Color(0xFF1E293B)
                drawLine(gridColor, Offset(0f, midY), Offset(canvasWidth, midY), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, canvasHeight * 0.25f), Offset(canvasWidth, canvasHeight * 0.25f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, canvasHeight * 0.75f), Offset(canvasWidth, canvasHeight * 0.75f), strokeWidth = 1f)

                // 1. Draw Frequency Spectrum Bars (when SPECTRUM or DUAL mode)
                if (mode == VisualizerMode.SPECTRUM || mode == VisualizerMode.DUAL) {
                    val barCount = frequencyBands.size.coerceAtLeast(1)
                    val barSpacing = 3.dp.toPx()
                    val totalSpacing = barSpacing * (barCount - 1)
                    val barWidth = ((canvasWidth - totalSpacing) / barCount).coerceAtLeast(2.dp.toPx())

                    val barGradient = Brush.verticalGradient(
                        colors = listOf(
                            primaryAccent,
                            secondaryAccent,
                            secondaryAccent.copy(alpha = 0.35f)
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )

                    for (i in 0 until barCount) {
                        val rawBandVal = frequencyBands.getOrElse(i) { 0.04f }
                        // When idle or paused, add gentle ambient wave
                        val bandVal = if (status == RecordingStatus.IDLE) {
                            (0.08f + 0.06f * sin(ambientPhase + i * 0.35f)).coerceIn(0.04f, 1f)
                        } else if (status == RecordingStatus.PAUSED) {
                            (rawBandVal * 0.45f).coerceIn(0.04f, 1f)
                        } else {
                            rawBandVal.coerceIn(0.04f, 1f)
                        }

                        val barHeight = (canvasHeight * 0.88f * bandVal).coerceIn(4.dp.toPx(), canvasHeight * 0.95f)
                        val left = i * (barWidth + barSpacing)
                        val top = canvasHeight - barHeight

                        // Vertical spectrum bar
                        drawRoundRect(
                            brush = barGradient,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                            alpha = if (mode == VisualizerMode.DUAL) 0.65f else 0.95f
                        )

                        // Peak cap floating dot (classic studio VU equalizer look)
                        if (status == RecordingStatus.RECORDING && bandVal > 0.12f) {
                            val capY = (top - 3.dp.toPx()).coerceAtLeast(0f)
                            drawCircle(
                                color = primaryAccent,
                                radius = barWidth / 2f,
                                center = Offset(left + barWidth / 2f, capY)
                            )
                        }
                    }
                }

                // 2. Draw Real-Time Oscilloscope Waveform (when WAVEFORM or DUAL mode)
                if (mode == VisualizerMode.WAVEFORM || mode == VisualizerMode.DUAL) {
                    val points = waveformPoints
                    if (points.isNotEmpty()) {
                        val path = Path()
                        val fillPath = Path()

                        val stepX = canvasWidth / (points.size - 1).coerceAtLeast(1)

                        fillPath.moveTo(0f, midY)

                        for (i in points.indices) {
                            val rawAmp = points[i]
                            val sample = if (status == RecordingStatus.IDLE) {
                                (0.12f * sin(ambientPhase + i * 0.4f))
                            } else if (status == RecordingStatus.PAUSED) {
                                (rawAmp * 0.35f)
                            } else {
                                rawAmp
                            }

                            val x = i * stepX
                            val y = midY - (sample * midY * 0.82f)

                            if (i == 0) {
                                path.moveTo(x, y)
                            } else {
                                val prevX = (i - 1) * stepX
                                val prevRaw = points[i - 1]
                                val prevSample = if (status == RecordingStatus.IDLE) {
                                    (0.12f * sin(ambientPhase + (i - 1) * 0.4f))
                                } else if (status == RecordingStatus.PAUSED) {
                                    (prevRaw * 0.35f)
                                } else {
                                    prevRaw
                                }
                                val prevY = midY - (prevSample * midY * 0.82f)
                                val cX = (prevX + x) / 2f
                                path.cubicTo(cX, prevY, cX, y, x, y)
                            }
                            fillPath.lineTo(x, y)
                        }

                        fillPath.lineTo(canvasWidth, midY)
                        fillPath.close()

                        // Gradient translucent fill underneath waveform
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    primaryAccent.copy(alpha = 0.25f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = canvasHeight
                            )
                        )

                        // Main neon waveform stroke
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(
                                colors = listOf(primaryAccent, secondaryAccent, primaryAccent)
                            ),
                            style = Stroke(
                                width = if (mode == VisualizerMode.DUAL) 2.5.dp.toPx() else 3.2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }

        // Bottom Frequency Bands Legend (Bass, Mid/Voice, Treble)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FrequencyZoneIndicator(label = "Graves (Bass)", range = "40-250 Hz", color = primaryAccent)
            FrequencyZoneIndicator(label = "Voz / Médios", range = "250-3 kHz", color = secondaryAccent)
            FrequencyZoneIndicator(label = "Agudos (Treble)", range = "3-11 kHz", color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun FrequencyZoneIndicator(
    label: String,
    range: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(6.dp)
        ) {}
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = range,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
