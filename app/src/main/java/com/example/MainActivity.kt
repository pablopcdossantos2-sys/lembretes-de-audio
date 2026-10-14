package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AudioReminderTheme
import com.example.ui.viewmodel.MainViewModel

enum class NavigationScreen(val title: String) {
    HOME("Início"),
    RECORD("Gravar"),
    LIBRARY("Biblioteca"),
    PROFILES("Perfis"),
    SETTINGS("Configurações")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Garante exibição e ativação da tela mesmo com o aparelho bloqueado
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContent {
            AudioReminderTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(NavigationScreen.HOME) }
    var openBatteryAssistantDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe user messages from ViewModel
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Refresh device state on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDeviceStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Handle system back button to return to Home screen
    BackHandler(enabled = currentScreen != NavigationScreen.HOME) {
        currentScreen = NavigationScreen.HOME
    }

    // Permission request for POST_NOTIFICATIONS (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth > 600.dp

        Scaffold(
            topBar = {
                if (currentScreen != NavigationScreen.RECORD) {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentScreen.title,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.HOME,
                            onClick = { currentScreen = NavigationScreen.HOME },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Início") },
                            label = { Text("Início") },
                            modifier = Modifier.testTag("nav_item_home")
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.RECORD,
                            onClick = { currentScreen = NavigationScreen.RECORD },
                            icon = { Icon(Icons.Default.Mic, contentDescription = "Gravar Lembrete") },
                            label = { Text("Gravar") },
                            modifier = Modifier.testTag("nav_item_record")
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.LIBRARY,
                            onClick = { currentScreen = NavigationScreen.LIBRARY },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Biblioteca") },
                            label = { Text("Biblioteca") },
                            modifier = Modifier.testTag("nav_item_library")
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.PROFILES,
                            onClick = { currentScreen = NavigationScreen.PROFILES },
                            icon = { Icon(Icons.Default.Schedule, contentDescription = "Perfis") },
                            label = { Text("Perfis") },
                            modifier = Modifier.testTag("nav_item_profiles")
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.SETTINGS,
                            onClick = { currentScreen = NavigationScreen.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Configurações") },
                            label = { Text("Config") },
                            modifier = Modifier.testTag("nav_item_settings")
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(modifier = Modifier.testTag("side_nav_rail")) {
                        NavigationRailItem(
                            selected = currentScreen == NavigationScreen.HOME,
                            onClick = { currentScreen = NavigationScreen.HOME },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Início") },
                            label = { Text("Início") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == NavigationScreen.RECORD,
                            onClick = { currentScreen = NavigationScreen.RECORD },
                            icon = { Icon(Icons.Default.Mic, contentDescription = "Gravar Lembrete") },
                            label = { Text("Gravar") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == NavigationScreen.LIBRARY,
                            onClick = { currentScreen = NavigationScreen.LIBRARY },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Biblioteca") },
                            label = { Text("Biblioteca") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == NavigationScreen.PROFILES,
                            onClick = { currentScreen = NavigationScreen.PROFILES },
                            icon = { Icon(Icons.Default.Schedule, contentDescription = "Perfis") },
                            label = { Text("Perfis") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == NavigationScreen.SETTINGS,
                            onClick = { currentScreen = NavigationScreen.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Configurações") },
                            label = { Text("Config") }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        NavigationScreen.HOME -> HomeScreen(
                            uiState = uiState,
                            viewModel = viewModel,
                            onNavigateToLibrary = { currentScreen = NavigationScreen.LIBRARY },
                            onOpenBatteryDialog = {
                                openBatteryAssistantDialog = true
                                currentScreen = NavigationScreen.SETTINGS
                            }
                        )
                        NavigationScreen.PROFILES -> ProfilesScreen(
                            uiState = uiState,
                            viewModel = viewModel
                        )
                        NavigationScreen.LIBRARY -> LibraryScreen(
                            uiState = uiState,
                            viewModel = viewModel,
                            onNavigateToRecord = { currentScreen = NavigationScreen.RECORD }
                        )
                        NavigationScreen.SETTINGS -> SettingsScreen(
                            uiState = uiState,
                            viewModel = viewModel,
                            showBatteryDialogDefault = openBatteryAssistantDialog,
                            onDismissBatteryDialog = { openBatteryAssistantDialog = false }
                        )
                        NavigationScreen.RECORD -> {
                            val lembreteViewModel: com.example.ui.viewmodel.LembreteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                            com.example.ui.screens.RecordReminderScreen(
                                lembreteViewModel = lembreteViewModel,
                                onNavigateBack = { currentScreen = NavigationScreen.LIBRARY }
                            )
                        }
                    }
                }
            }
        }
    }
}
