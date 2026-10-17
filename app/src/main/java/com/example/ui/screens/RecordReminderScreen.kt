package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.example.ui.components.SimpleTimePickerDialog
import com.example.ui.components.AudioWaveformFrequencyVisualizer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.core.content.ContextCompat
import com.example.player.AudioPlayerManager
import com.example.ui.viewmodel.LembreteViewModel
import com.example.util.AudioRecorderHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class RecordingStatus(val label: String, val colorDescription: String) {
    IDLE("Pronto para Gravar", "Aguardando"),
    RECORDING("Gravando áudio...", "Capturando microfone"),
    PAUSED("Gravação Pausada", "Pausa ativa"),
    FINISHED("Gravação Concluída", "Pronto para salvar")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordReminderScreen(
    lembreteViewModel: LembreteViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hardware back navigation handler
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Audio recorder helper & preview player
    val recorderHelper = remember { AudioRecorderHelper(context) }
    val previewPlayer = remember { AudioPlayerManager(context) }

    // Permission state
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedExplanation by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordPermission = isGranted
        if (!isGranted) {
            permissionDeniedExplanation = true
        }
    }

    // Recording and UI state
    var recordingStatus by remember { mutableStateOf(RecordingStatus.IDLE) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var durationMillis by remember { mutableLongStateOf(0L) }
    var amplitudeLevel by remember { mutableFloatStateOf(0f) }

    // Live frequency data, waveform, dB, and peak frequency collected from recorderHelper
    val frequencyBands by recorderHelper.frequencyData.collectAsStateWithLifecycle()
    val waveformPoints by recorderHelper.waveformData.collectAsStateWithLifecycle()
    val decibels by recorderHelper.decibels.collectAsStateWithLifecycle()
    val peakFrequencyHz by recorderHelper.peakFrequencyHz.collectAsStateWithLifecycle()

    // Preview state
    var isPlayingPreview by remember { mutableStateOf(false) }

    // Reminder Metadata form fields
    val availableProfiles by lembreteViewModel.profilesState.collectAsStateWithLifecycle()
    var selectedProfileId by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf("") }
    var periodicityMinutes by remember { mutableIntStateOf(15) }
    var hasRestPeriod by remember { mutableStateOf(false) } // Padrão: SEM descanso programado
    var startTime by remember { mutableStateOf(LocalTime.of(8, 0)) }
    var endTime by remember { mutableStateOf(LocalTime.of(22, 0)) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // Cleanup when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            if (recorderHelper.isRecording) {
                recorderHelper.stopRecording()
            }
            recorderHelper.release()
            previewPlayer.stop()
        }
    }

    // Timer & Amplitude sampler coroutine
    LaunchedEffect(recordingStatus) {
        if (recordingStatus == RecordingStatus.RECORDING) {
            val startTime = System.currentTimeMillis() - durationMillis
            while (recordingStatus == RecordingStatus.RECORDING) {
                durationMillis = System.currentTimeMillis() - startTime
                val rawAmp = recorderHelper.getMaxAmplitude()
                amplitudeLevel = (rawAmp / 32767f).coerceIn(0.05f, 1f)
                delay(60)
            }
        }
    }

    Scaffold(
        modifier = modifier.testTag("record_reminder_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Gravar Lembrete",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (recorderHelper.isRecording) {
                                recorderHelper.stopRecording()
                            }
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("record_screen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Banner (if not granted)
            if (!hasRecordPermission) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permission_banner"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Permissão de Microfone Necessária",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (permissionDeniedExplanation) {
                                "O app precisa de acesso ao microfone para gravar seus lembretes personalizados. Por favor, conceda a permissão nas Configurações do sistema."
                            } else {
                                "Para gravar sua voz e criar lembretes sonoros personalizados, autorize o microfone abaixo."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (permissionDeniedExplanation) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null)
                                            )
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.testTag("open_settings_permission_button")
                                ) {
                                    Text("Abrir Configurações")
                                }
                            }
                            Button(
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier.testTag("request_permission_button")
                            ) {
                                Text("Permitir Gravação")
                            }
                        }
                    }
                }
            }

            // Real-Time Status Card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recording_status_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = when (recordingStatus) {
                        RecordingStatus.RECORDING -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        RecordingStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        RecordingStatus.FINISHED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        RecordingStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Pulsing Visual indicator
                    StatusIndicatorBadge(status = recordingStatus)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Timer Display (HH:MM:SS or MM:SS)
                    val seconds = (durationMillis / 1000) % 60
                    val minutes = (durationMillis / (1000 * 60)) % 60
                    val millisTens = (durationMillis % 1000) / 100
                    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d.%01d", minutes, seconds, millisTens)

                    Text(
                        text = formattedTime,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when (recordingStatus) {
                            RecordingStatus.RECORDING -> MaterialTheme.colorScheme.error
                            RecordingStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("timer_display")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = recordingStatus.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = recordingStatus.colorDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Real-Time Audio Frequency Spectrum & Waveform Visualizer
                    AudioWaveformFrequencyVisualizer(
                        status = recordingStatus,
                        frequencyBands = frequencyBands,
                        waveformPoints = waveformPoints,
                        decibels = decibels,
                        peakFrequencyHz = peakFrequencyHz,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Real-Time Audio Control Action Buttons (Iniciar, Pausar, Finalizar)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recording_controls_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Controles de Gravação",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (recordingStatus) {
                            RecordingStatus.IDLE -> {
                                // Start recording action button
                                Button(
                                    onClick = {
                                        if (!hasRecordPermission) {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@Button
                                        }
                                        val file = recorderHelper.createDefaultOutputFile()
                                        currentFile = file
                                        durationMillis = 0L
                                        val started = recorderHelper.startRecording(file)
                                        if (started) {
                                            recordingStatus = RecordingStatus.RECORDING
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Falha ao iniciar microfone")
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("record_start_button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Iniciar Gravação",
                                        tint = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            RecordingStatus.RECORDING -> {
                                // Pause Button
                                OutlinedButton(
                                    onClick = {
                                        val paused = recorderHelper.pauseRecording()
                                        if (paused) {
                                            recordingStatus = RecordingStatus.PAUSED
                                        }
                                    },
                                    modifier = Modifier
                                        .size(60.dp)
                                        .testTag("record_pause_button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Pausar Gravação",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                // Stop / Finish Button
                                Button(
                                    onClick = {
                                        recorderHelper.stopRecording()
                                        recordingStatus = RecordingStatus.FINISHED
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("record_stop_button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Finalizar Gravação",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            RecordingStatus.PAUSED -> {
                                // Resume Button
                                Button(
                                    onClick = {
                                        val resumed = recorderHelper.resumeRecording()
                                        if (resumed) {
                                            recordingStatus = RecordingStatus.RECORDING
                                        }
                                    },
                                    modifier = Modifier
                                        .size(60.dp)
                                        .testTag("record_resume_button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Retomar Gravação",
                                        tint = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                // Stop / Finish Button
                                Button(
                                    onClick = {
                                        recorderHelper.stopRecording()
                                        recordingStatus = RecordingStatus.FINISHED
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("record_stop_paused_button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Finalizar Gravação",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            RecordingStatus.FINISHED -> {
                                // Reset / Record Again
                                OutlinedButton(
                                    onClick = {
                                        previewPlayer.stop()
                                        isPlayingPreview = false
                                        currentFile?.delete()
                                        currentFile = null
                                        durationMillis = 0L
                                        recordingStatus = RecordingStatus.IDLE
                                    },
                                    modifier = Modifier.testTag("record_restart_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Gravar Novamente")
                                }

                                // Preview recorded audio
                                Button(
                                    onClick = {
                                        currentFile?.let { file ->
                                            if (isPlayingPreview) {
                                                previewPlayer.stop()
                                                isPlayingPreview = false
                                            } else {
                                                val uri = Uri.fromFile(file).toString()
                                                previewPlayer.playAudio(
                                                    uriString = uri,
                                                    targetVolume = 1.0f,
                                                    fadeInDurationMs = 0L,
                                                    onComplete = {
                                                        isPlayingPreview = false
                                                    }
                                                )
                                                isPlayingPreview = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("record_preview_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingPreview) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPlayingPreview) "Parar Ouvir" else "Ouvir Áudio")
                                }
                            }
                        }
                    }
                }
            }

            // Save Reminder to Room Database (Active once recording finishes)
            AnimatedVisibility(visible = recordingStatus == RecordingStatus.FINISHED) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_reminder_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Detalhes do Lembrete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 1. Vínculo ao Perfil de Usuário
                        Text(
                            text = "Perfil de Usuário:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = selectedProfileId == null,
                                onClick = { selectedProfileId = null },
                                label = { Text("Novo Perfil Individual") },
                                leadingIcon = if (selectedProfileId == null) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.testTag("profile_chip_new")
                            )

                            availableProfiles.forEach { profileWithRests ->
                                val p = profileWithRests.profile
                                FilterChip(
                                    selected = selectedProfileId == p.id,
                                    onClick = { selectedProfileId = p.id },
                                    label = { Text(p.name) },
                                    leadingIcon = if (selectedProfileId == p.id) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else {
                                        { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    },
                                    modifier = Modifier.testTag("profile_chip_${p.id}")
                                )
                            }
                        }

                        // 2. Título do Lembrete
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Título do Lembrete") },
                            placeholder = { Text("Ex: Beber água, Tomar remédio") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reminder_title_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 3. Periodicidade Individual
                        Text(
                            text = "Periodicidade:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val options = listOf(5, 10, 15, 30, 45, 60, 120)
                            options.forEach { minutes ->
                                FilterChip(
                                    selected = periodicityMinutes == minutes,
                                    onClick = { periodicityMinutes = minutes },
                                    label = {
                                        Text(if (minutes >= 60) "${minutes / 60}h" else "${minutes}m")
                                    },
                                    leadingIcon = if (periodicityMinutes == minutes) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(),
                                    modifier = Modifier.testTag("periodicity_chip_${minutes}m")
                                )
                            }
                        }

                        // 4. Período de Descanso (Opcional - desligado por padrão)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("resting_period_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bedtime,
                                            contentDescription = null,
                                            tint = if (hasRestPeriod) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "Período de Descanso",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (hasRestPeriod) "Descanso programado ativado" else "Sem descanso (ativo 24h contínuo)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = hasRestPeriod,
                                        onCheckedChange = { hasRestPeriod = it },
                                        modifier = Modifier.testTag("has_rest_period_switch")
                                    )
                                }

                                if (hasRestPeriod) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { showStartTimePicker = true },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("start_time_button"),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(String.format(Locale.getDefault(), "Início: %02d:%02d", startTime.hour, startTime.minute))
                                        }

                                        OutlinedButton(
                                            onClick = { showEndTimePicker = true },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("end_time_button"),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(String.format(Locale.getDefault(), "Fim: %02d:%02d", endTime.hour, endTime.minute))
                                        }
                                    }

                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = String.format(
                                                Locale.getDefault(),
                                                "Horário Ativo das %02d:%02d às %02d:%02d. O lembrete descansará silenciosamente fora desta janela.",
                                                startTime.hour,
                                                startTime.minute,
                                                endTime.hour,
                                                endTime.minute
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "Sem descanso programado. O lembrete tocará continuamente no intervalo selecionado a qualquer hora do dia ou da noite.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (isSaving) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Button(
                            onClick = {
                                val file = currentFile ?: return@Button
                                isSaving = true
                                lembreteViewModel.salvarLembreteGravado(
                                    titulo = title.ifBlank { "Lembrete Gravado" },
                                    arquivoAudio = file,
                                    periodicidadeMinutos = periodicityMinutes,
                                    selectedProfileId = selectedProfileId,
                                    hasRestPeriod = hasRestPeriod,
                                    horaInicio = startTime.hour,
                                    minutoInicio = startTime.minute,
                                    horaFim = endTime.hour,
                                    minutoFim = endTime.minute,
                                    onSucesso = {
                                        isSaving = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Lembrete salvo com sucesso no banco Room!")
                                        }
                                        onNavigateBack()
                                    }
                                )
                            },
                            enabled = !isSaving && currentFile != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("save_reminder_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Salvar no Banco Local (Room)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

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
            title = "Fim do Funcionamento (Início do Descanso)",
            initialTime = endTime,
            onConfirm = {
                endTime = it
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }
}

@Composable
private fun StatusIndicatorBadge(status: RecordingStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val color by animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.RECORDING -> MaterialTheme.colorScheme.error
            RecordingStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
            RecordingStatus.FINISHED -> MaterialTheme.colorScheme.primary
            RecordingStatus.IDLE -> MaterialTheme.colorScheme.outline
        },
        label = "status_color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(54.dp)
    ) {
        if (status == RecordingStatus.RECORDING) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f))
            )
        }
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (status) {
                        RecordingStatus.RECORDING -> Icons.Default.Mic
                        RecordingStatus.PAUSED -> Icons.Default.Pause
                        RecordingStatus.FINISHED -> Icons.Default.Check
                        RecordingStatus.IDLE -> Icons.Default.GraphicEq
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

