package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

enum class DiagnosticLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

enum class DiagnosticCategory {
    PLAYBACK,
    ROUTING,
    ALARM,
    SERVICE,
    WORKER,
    SYSTEM
}

data class DiagnosticLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = formatTimestamp(timestamp),
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val message: String,
    val details: String? = null
) {
    companion object {
        private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM")

        fun formatTimestamp(epochMillis: Long): String {
            return try {
                val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                zdt.format(formatter)
            } catch (_: Exception) {
                epochMillis.toString()
            }
        }
    }
}

/**
 * Sistema centralizado de registro diagnóstico para rastrear eventos de reprodução de áudio,
 * roteamento (fones/alto-falante), agendamento e disparo de alarmes, e execução do serviço/worker.
 */
object AudioDiagnosticLogger {

    private const val TAG = "AudioDiagnosticLogger"
    private const val MAX_LOGS = 250
    private const val LOGS_FILE_NAME = "audio_diagnostics_history.json"

    private val deque = ConcurrentLinkedDeque<DiagnosticLogEntry>()
    private val _logsFlow = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<DiagnosticLogEntry>> = _logsFlow.asStateFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true
        loadPersistedLogs()
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.SYSTEM,
            message = "Mecanismo de diagnósticos inicializado",
            details = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - Modelo: ${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun log(
        level: DiagnosticLevel,
        category: DiagnosticCategory,
        message: String,
        details: String? = null
    ) {
        val entry = DiagnosticLogEntry(
            level = level,
            category = category,
            message = message,
            details = details
        )

        deque.addFirst(entry)
        while (deque.size > MAX_LOGS) {
            deque.pollLast()
        }

        _logsFlow.value = deque.toList()

        when (level) {
            DiagnosticLevel.INFO -> Log.i(TAG, "[${category.name}] $message - ${details ?: ""}")
            DiagnosticLevel.SUCCESS -> Log.i(TAG, "✓ [${category.name}] $message - ${details ?: ""}")
            DiagnosticLevel.WARN -> Log.w(TAG, "⚠ [${category.name}] $message - ${details ?: ""}")
            DiagnosticLevel.ERROR -> Log.e(TAG, "✗ [${category.name}] $message - ${details ?: ""}")
        }

        persistLogsDebounced()
    }

    fun logPlaybackStart(
        title: String,
        uri: String,
        isHeadphones: Boolean,
        volumePercent: Int,
        streamType: String,
        details: String? = null
    ) {
        val routeStr = if (isHeadphones) "FONES DE OUVIDO (Bluetooth / Fio)" else "ALTO-FALANTE DO APARELHO (Speaker)"
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.PLAYBACK,
            message = "Iniciando reprodução: \"$title\"",
            details = "Saída: $routeStr | Stream: $streamType | Volume: $volumePercent% | URI: $uri${if (details != null) " | $details" else ""}"
        )
    }

    fun logPlaybackEnd(
        title: String,
        durationMs: Long? = null,
        success: Boolean = true,
        details: String? = null
    ) {
        val durStr = if (durationMs != null) " (${durationMs}ms)" else ""
        if (success) {
            log(
                level = DiagnosticLevel.SUCCESS,
                category = DiagnosticCategory.PLAYBACK,
                message = "Reprodução concluída com sucesso: \"$title\"$durStr",
                details = details ?: "Foco e volumes restaurados normalmente"
            )
        } else {
            log(
                level = DiagnosticLevel.WARN,
                category = DiagnosticCategory.PLAYBACK,
                message = "Reprodução finalizada com aviso: \"$title\"$durStr",
                details = details
            )
        }
    }

    fun logPlaybackError(
        title: String,
        errorMessage: String,
        details: String? = null
    ) {
        log(
            level = DiagnosticLevel.ERROR,
            category = DiagnosticCategory.PLAYBACK,
            message = "Falha na reprodução de \"$title\": $errorMessage",
            details = details
        )
    }

    fun logAudioRouting(
        isHeadphones: Boolean,
        deviceSummary: String
    ) {
        val dest = if (isHeadphones) "Fones de ouvido detectados" else "Alto-falante do celular ativo"
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.ROUTING,
            message = "Roteamento de áudio: $dest",
            details = "Dispositivos ativos: $deviceSummary"
        )
    }

    fun logAlarmScheduled(
        triggerTimeStr: String,
        triggerMillis: Long,
        method: String,
        canScheduleExact: Boolean
    ) {
        val delaySec = ((triggerMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.ALARM,
            message = "Alarme programado para $triggerTimeStr (daqui a ${delaySec}s)",
            details = "Método: $method | Permissão de alarme exato: ${if (canScheduleExact) "SIM" else "NÃO (usando fallback seguro)"}"
        )
    }

    fun logAlarmTriggered(
        expectedMillis: Long,
        actualMillis: Long,
        profileName: String?
    ) {
        val diffMs = actualMillis - expectedMillis
        val diffStr = if (expectedMillis > 0) " (Desvio: ${diffMs}ms)" else ""
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.ALARM,
            message = "Disparo de alarme recebido pelo sistema$diffStr",
            details = "Perfil ativo: ${profileName ?: "Lembrete Geral / Automático"}"
        )
    }

    fun logServiceEvent(
        action: String,
        message: String,
        details: String? = null
    ) {
        log(
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.SERVICE,
            message = "Serviço ($action): $message",
            details = details
        )
    }

    fun clearLogs() {
        deque.clear()
        _logsFlow.value = emptyList()
        ioScope.launch {
            try {
                appContext?.let { ctx ->
                    val file = File(ctx.filesDir, LOGS_FILE_NAME)
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing logs file", e)
            }
        }
    }

    fun getLogsAsFormattedText(): String {
        val list = deque.toList()
        if (list.isEmpty()) return "Nenhum registro de diagnóstico capturado até o momento."

        val sb = StringBuilder()
        sb.append("=== DIAGNÓSTICO DE ÁUDIO E LEMBRETES ===\n")
        sb.append("Data da exportação: ${DiagnosticLogEntry.formatTimestamp(System.currentTimeMillis())}\n")
        sb.append("Total de registros: ${list.size}\n\n")

        list.forEach { entry ->
            val symbol = when (entry.level) {
                DiagnosticLevel.INFO -> "[INFO]"
                DiagnosticLevel.SUCCESS -> "[SUCESSO]"
                DiagnosticLevel.WARN -> "[AVISO]"
                DiagnosticLevel.ERROR -> "[ERRO]"
            }
            sb.append("${entry.formattedTime} $symbol [${entry.category.name}] ${entry.message}\n")
            if (!entry.details.isNullOrBlank()) {
                sb.append("   └ Detalhes: ${entry.details}\n")
            }
        }
        return sb.toString()
    }

    private fun persistLogsDebounced() {
        val ctx = appContext ?: return
        ioScope.launch {
            try {
                val list = deque.take(150)
                val jsonArray = JSONArray()
                for (item in list) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("timestamp", item.timestamp)
                        put("formattedTime", item.formattedTime)
                        put("level", item.level.name)
                        put("category", item.category.name)
                        put("message", item.message)
                        put("details", item.details ?: "")
                    }
                    jsonArray.put(obj)
                }
                val file = File(ctx.filesDir, LOGS_FILE_NAME)
                file.writeText(jsonArray.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist diagnostic logs", e)
            }
        }
    }

    private fun loadPersistedLogs() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, LOGS_FILE_NAME)
            if (!file.exists()) return
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return
            val jsonArray = JSONArray(jsonStr)
            val loaded = mutableListOf<DiagnosticLogEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val level = try { DiagnosticLevel.valueOf(obj.getString("level")) } catch (_: Exception) { DiagnosticLevel.INFO }
                val category = try { DiagnosticCategory.valueOf(obj.getString("category")) } catch (_: Exception) { DiagnosticCategory.SYSTEM }
                val details = obj.optString("details").ifBlank { null }
                loaded.add(
                    DiagnosticLogEntry(
                        id = obj.optLong("id", System.nanoTime()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        formattedTime = obj.optString("formattedTime", ""),
                        level = level,
                        category = category,
                        message = obj.getString("message"),
                        details = details
                    )
                )
            }
            deque.clear()
            deque.addAll(loaded)
            _logsFlow.value = loaded
        } catch (e: Exception) {
            Log.w(TAG, "Could not load persisted diagnostic logs", e)
        }
    }
}
