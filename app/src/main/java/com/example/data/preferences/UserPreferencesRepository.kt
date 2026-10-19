package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

data class UserPreferences(
    val appLanguage: String = "pt-BR",
    val isAppMasterEnabled: Boolean = false,
    val masterVolumePercent: Int = 100,
    val useAppVolumeOnly: Boolean = false,
    val fadeInEnabled: Boolean = true,
    val fadeInDurationSeconds: Float = 2.0f,
    val vibrateWithAudio: Boolean = true,
    val showLabelInNotification: Boolean = true,
    val respectDoNotDisturb: Boolean = true,
    val defaultResumePolicy: String = "NEXT_GRID_MARK",
    val snoozeUntilEpochMillis: Long = 0L,
    val lastSequentialIndex: Int = 0,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = 22,
    val quietHoursStartMinute: Int = 0,
    val quietHoursEndHour: Int = 7,
    val quietHoursEndMinute: Int = 0,
    val quietHoursDaysCsv: String = "1,2,3,4,5,6,7",
    val quietHoursNotifySilently: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val APP_MASTER_ENABLED = booleanPreferencesKey("app_master_enabled")
        val MASTER_VOLUME_PERCENT = intPreferencesKey("master_volume_percent")
        val USE_APP_VOLUME_ONLY = booleanPreferencesKey("use_app_volume_only")
        val FADE_IN_ENABLED = booleanPreferencesKey("fade_in_enabled")
        val FADE_IN_DURATION_SEC = floatPreferencesKey("fade_in_duration_sec")
        val VIBRATE_WITH_AUDIO = booleanPreferencesKey("vibrate_with_audio")
        val SHOW_LABEL_IN_NOTIFICATION = booleanPreferencesKey("show_label_in_notification")
        val RESPECT_DO_NOT_DISTURB = booleanPreferencesKey("respect_do_not_disturb")
        val DEFAULT_RESUME_POLICY = stringPreferencesKey("default_resume_policy")
        val SNOOZE_UNTIL_EPOCH_MILLIS = longPreferencesKey("snooze_until_epoch_millis")
        val LAST_SEQUENTIAL_INDEX = intPreferencesKey("last_sequential_index")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START_HOUR = intPreferencesKey("quiet_hours_start_hour")
        val QUIET_HOURS_START_MINUTE = intPreferencesKey("quiet_hours_start_minute")
        val QUIET_HOURS_END_HOUR = intPreferencesKey("quiet_hours_end_hour")
        val QUIET_HOURS_END_MINUTE = intPreferencesKey("quiet_hours_end_minute")
        val QUIET_HOURS_DAYS_CSV = stringPreferencesKey("quiet_hours_days_csv")
        val QUIET_HOURS_NOTIFY_SILENTLY = booleanPreferencesKey("quiet_hours_notify_silently")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            appLanguage = prefs[Keys.APP_LANGUAGE] ?: "pt-BR",
            isAppMasterEnabled = prefs[Keys.APP_MASTER_ENABLED] ?: false,
            masterVolumePercent = prefs[Keys.MASTER_VOLUME_PERCENT] ?: 100,
            useAppVolumeOnly = prefs[Keys.USE_APP_VOLUME_ONLY] ?: false,
            fadeInEnabled = prefs[Keys.FADE_IN_ENABLED] ?: true,
            fadeInDurationSeconds = prefs[Keys.FADE_IN_DURATION_SEC] ?: 2.0f,
            vibrateWithAudio = prefs[Keys.VIBRATE_WITH_AUDIO] ?: true,
            showLabelInNotification = prefs[Keys.SHOW_LABEL_IN_NOTIFICATION] ?: true,
            respectDoNotDisturb = prefs[Keys.RESPECT_DO_NOT_DISTURB] ?: true,
            defaultResumePolicy = prefs[Keys.DEFAULT_RESUME_POLICY] ?: "NEXT_GRID_MARK",
            snoozeUntilEpochMillis = prefs[Keys.SNOOZE_UNTIL_EPOCH_MILLIS] ?: 0L,
            lastSequentialIndex = prefs[Keys.LAST_SEQUENTIAL_INDEX] ?: 0,
            quietHoursEnabled = prefs[Keys.QUIET_HOURS_ENABLED] ?: false,
            quietHoursStartHour = prefs[Keys.QUIET_HOURS_START_HOUR] ?: 22,
            quietHoursStartMinute = prefs[Keys.QUIET_HOURS_START_MINUTE] ?: 0,
            quietHoursEndHour = prefs[Keys.QUIET_HOURS_END_HOUR] ?: 7,
            quietHoursEndMinute = prefs[Keys.QUIET_HOURS_END_MINUTE] ?: 0,
            quietHoursDaysCsv = prefs[Keys.QUIET_HOURS_DAYS_CSV] ?: "1,2,3,4,5,6,7",
            quietHoursNotifySilently = prefs[Keys.QUIET_HOURS_NOTIFY_SILENTLY] ?: false
        )
    }

    suspend fun getPreferences(): UserPreferences = preferencesFlow.first()

    suspend fun setAppMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_MASTER_ENABLED] = enabled }
    }

    suspend fun setMasterVolumePercent(volume: Int) {
        context.dataStore.edit { it[Keys.MASTER_VOLUME_PERCENT] = volume.coerceIn(0, 100) }
    }

    suspend fun setUseAppVolumeOnly(useAppVolume: Boolean) {
        context.dataStore.edit { it[Keys.USE_APP_VOLUME_ONLY] = useAppVolume }
    }

    suspend fun setFadeInEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FADE_IN_ENABLED] = enabled }
    }

    suspend fun setFadeInDurationSeconds(seconds: Float) {
        context.dataStore.edit { it[Keys.FADE_IN_DURATION_SEC] = seconds.coerceIn(0.5f, 10.0f) }
    }

    suspend fun setVibrateWithAudio(vibrate: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATE_WITH_AUDIO] = vibrate }
    }

    suspend fun setShowLabelInNotification(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_LABEL_IN_NOTIFICATION] = show }
    }

    suspend fun setRespectDoNotDisturb(respect: Boolean) {
        context.dataStore.edit { it[Keys.RESPECT_DO_NOT_DISTURB] = respect }
    }

    suspend fun setDefaultResumePolicy(policy: String) {
        context.dataStore.edit { it[Keys.DEFAULT_RESUME_POLICY] = policy }
    }

    suspend fun setSnoozeUntilEpochMillis(epochMillis: Long) {
        context.dataStore.edit { it[Keys.SNOOZE_UNTIL_EPOCH_MILLIS] = epochMillis }
    }

    suspend fun incrementSequentialIndex() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LAST_SEQUENTIAL_INDEX] ?: 0
            prefs[Keys.LAST_SEQUENTIAL_INDEX] = current + 1
        }
    }

    suspend fun setAppLanguage(language: String) {
        val supported = setOf("pt-BR", "en", "es")
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = if (language in supported) language else "pt-BR" }
    }

    suspend fun setQuietHours(
        enabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysCsv: String,
        notifySilently: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.QUIET_HOURS_ENABLED] = enabled
            prefs[Keys.QUIET_HOURS_START_HOUR] = startHour.coerceIn(0, 23)
            prefs[Keys.QUIET_HOURS_START_MINUTE] = startMinute.coerceIn(0, 59)
            prefs[Keys.QUIET_HOURS_END_HOUR] = endHour.coerceIn(0, 23)
            prefs[Keys.QUIET_HOURS_END_MINUTE] = endMinute.coerceIn(0, 59)
            prefs[Keys.QUIET_HOURS_DAYS_CSV] = daysCsv
            prefs[Keys.QUIET_HOURS_NOTIFY_SILENTLY] = notifySilently
        }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.QUIET_HOURS_ENABLED] = enabled }
    }

    suspend fun setQuietHoursStart(startHour: Int, startMinute: Int) {
        context.dataStore.edit {
            it[Keys.QUIET_HOURS_START_HOUR] = startHour.coerceIn(0, 23)
            it[Keys.QUIET_HOURS_START_MINUTE] = startMinute.coerceIn(0, 59)
        }
    }

    suspend fun setQuietHoursEnd(endHour: Int, endMinute: Int) {
        context.dataStore.edit {
            it[Keys.QUIET_HOURS_END_HOUR] = endHour.coerceIn(0, 23)
            it[Keys.QUIET_HOURS_END_MINUTE] = endMinute.coerceIn(0, 59)
        }
    }

    suspend fun setQuietHoursDaysCsv(daysCsv: String) {
        context.dataStore.edit { it[Keys.QUIET_HOURS_DAYS_CSV] = daysCsv }
    }

    suspend fun setQuietHoursNotifySilently(notify: Boolean) {
        context.dataStore.edit { it[Keys.QUIET_HOURS_NOTIFY_SILENTLY] = notify }
    }
}
