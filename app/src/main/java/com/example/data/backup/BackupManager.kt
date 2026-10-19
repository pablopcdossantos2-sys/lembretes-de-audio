package com.example.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.ReminderEntity
import com.example.data.db.RestingPeriodEntity
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val prefsRepo: UserPreferencesRepository
) {

    suspend fun exportConfig(outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            root.put("version", BACKUP_VERSION)
            root.put("exportTimestamp", System.currentTimeMillis())

            val prefs = prefsRepo.getPreferences()
            root.put("preferences", JSONObject().apply {
                put("isAppMasterEnabled", prefs.isAppMasterEnabled)
                put("masterVolumePercent", prefs.masterVolumePercent)
                put("useAppVolumeOnly", prefs.useAppVolumeOnly)
                put("fadeInEnabled", prefs.fadeInEnabled)
                put("fadeInDurationSeconds", prefs.fadeInDurationSeconds.toDouble())
                put("vibrateWithAudio", prefs.vibrateWithAudio)
                put("showLabelInNotification", prefs.showLabelInNotification)
                put("respectDoNotDisturb", prefs.respectDoNotDisturb)
                put("defaultResumePolicy", prefs.defaultResumePolicy)
                put("quietHoursEnabled", prefs.quietHoursEnabled)
                put("quietHoursStartHour", prefs.quietHoursStartHour)
                put("quietHoursStartMinute", prefs.quietHoursStartMinute)
                put("quietHoursEndHour", prefs.quietHoursEndHour)
                put("quietHoursEndMinute", prefs.quietHoursEndMinute)
                put("quietHoursDaysCsv", prefs.quietHoursDaysCsv)
                put("quietHoursNotifySilently", prefs.quietHoursNotifySilently)
            })

            val profiles = database.profileDao().getAllProfilesWithRests()
            root.put("profiles", JSONArray().apply {
                profiles.forEach { item ->
                    val p = item.profile
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("isEnabled", p.isEnabled)
                        put("priority", p.priority)
                        put("daysOfWeekCsv", p.daysOfWeekCsv)
                        put("startHour", p.startHour)
                        put("startMinute", p.startMinute)
                        put("endHour", p.endHour)
                        put("endMinute", p.endMinute)
                        put("intervalMinutes", p.intervalMinutes)
                        put("playbackOrder", p.playbackOrder)
                        put("specificAudioId", p.specificAudioId ?: JSONObject.NULL)
                        put("audioIdsCsv", p.audioIdsCsv)
                        put("resumePolicy", p.resumePolicy)
                        put("fadeInOverrideEnabled", p.fadeInOverrideEnabled)
                        put("fadeInDurationSeconds", p.fadeInDurationSeconds.toDouble())
                        put("restingPeriods", JSONArray().apply {
                            item.restingPeriods.forEach { rest ->
                                put(JSONObject().apply {
                                    put("id", rest.id)
                                    put("label", rest.label)
                                    put("startHour", rest.startHour)
                                    put("startMinute", rest.startMinute)
                                    put("endHour", rest.endHour)
                                    put("endMinute", rest.endMinute)
                                })
                            }
                        })
                    })
                }
            })

            root.put("audios", JSONArray().apply {
                database.audioDao().getAllAudios().forEach { audio ->
                    put(JSONObject().apply {
                        put("id", audio.id)
                        put("uriString", audio.uriString)
                        put("label", audio.label)
                        put("weight", audio.weight)
                        put("volumePercent", audio.volumePercent)
                        put("isActive", audio.isActive)
                        put("audioType", audio.audioType)
                        put("ttsText", audio.ttsText ?: JSONObject.NULL)
                        put("dateAdded", audio.dateAdded)
                    })
                }
            })

            root.put("reminders", JSONArray().apply {
                database.reminderDao().getAllReminders().forEach { reminder ->
                    put(JSONObject().apply {
                        put("id", reminder.id)
                        put("profileId", reminder.profileId ?: JSONObject.NULL)
                        put("title", reminder.title)
                        put("text", reminder.text)
                        put("audioFilePath", reminder.audioFilePath ?: JSONObject.NULL)
                        put("audioUri", reminder.audioUri ?: JSONObject.NULL)
                        put("intervalMinutes", reminder.intervalMinutes)
                        put("isEnabled", reminder.isEnabled)
                        put("hasRestPeriod", reminder.hasRestPeriod)
                        put("startHour", reminder.startHour)
                        put("startMinute", reminder.startMinute)
                        put("endHour", reminder.endHour)
                        put("endMinute", reminder.endMinute)
                        put("daysOfWeekCsv", reminder.daysOfWeekCsv)
                        put("dateCreated", reminder.dateCreated)
                    })
                }
            })

            outputStream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            outputStream.flush()
            true
        } catch (_: Exception) {
            false
        } finally {
            try { outputStream.close() } catch (_: Exception) {}
        }
    }

    suspend fun importConfig(inputStream: InputStream): ImportResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val version = root.optInt("version", 0)
            if (version !in 1..BACKUP_VERSION) {
                return@withContext ImportResult(
                    success = false,
                    message = "Versão de backup não suportada: $version."
                )
            }

            // Validate the document shape before writing anything to the database.
            val profilesArray = root.optJSONArray("profiles") ?: JSONArray()
            val audiosArray = root.optJSONArray("audios") ?: JSONArray()
            val remindersArray = root.optJSONArray("reminders") ?: JSONArray()
            val prefsObject = root.optJSONObject("preferences")

            val audioIdMap = mutableMapOf<Long, Long>()
            val profileIdMap = mutableMapOf<Long, Long>()
            var importedAudios = 0
            var importedProfiles = 0
            var importedReminders = 0

            // All database writes are atomic: any parsing/SQL failure rolls them back.
            database.withTransaction {
                // Import audio first so profiles can point to the newly generated audio IDs.
                for (i in 0 until audiosArray.length()) {
                    val item = audiosArray.getJSONObject(i)
                    val oldId = item.optLong("id", 0L)
                    val uri = item.optString("uriString", "")
                    val uriIsLocalFile = uri.startsWith("file://")
                    val localFileAvailable = !uriIsLocalFile || runCatching {
                        File(uri.removePrefix("file://")).exists()
                    }.getOrDefault(false)
                    val declaredType = item.optString("audioType", "AUDIO_FILE")
                    val entity = AudioItemEntity(
                        id = 0L,
                        uriString = uri,
                        label = item.optString("label", "Áudio"),
                        weight = item.optInt("weight", 3).coerceIn(1, 100),
                        volumePercent = item.optInt("volumePercent", 100).coerceIn(0, 100),
                        isActive = item.optBoolean("isActive", true) && localFileAvailable,
                        audioType = declaredType,
                        ttsText = item.optNullableString("ttsText"),
                        dateAdded = item.optLong("dateAdded", System.currentTimeMillis())
                    )
                    val newId = database.audioDao().insertAudio(entity)
                    if (oldId > 0L) audioIdMap[oldId] = newId
                    importedAudios++
                }

                for (i in 0 until profilesArray.length()) {
                    val item = profilesArray.getJSONObject(i)
                    val oldId = item.optLong("id", 0L)
                    val oldSpecificAudioId = item.optLongOrNull("specificAudioId")
                    val mappedSpecificAudioId = oldSpecificAudioId?.let { audioIdMap[it] }
                    val mappedAudioIds = item.optString("audioIdsCsv", "")
                        .split(",")
                        .mapNotNull { it.trim().toLongOrNull() }
                        .mapNotNull { audioIdMap[it] }
                        .distinct()
                        .joinToString(",")

                    val profile = ProfileEntity(
                        id = 0L,
                        name = item.optString("name", "Perfil Importado"),
                        isEnabled = item.optBoolean("isEnabled", true),
                        priority = item.optInt("priority", 1),
                        daysOfWeekCsv = item.optString("daysOfWeekCsv", "1,2,3,4,5,6,7"),
                        startHour = item.optInt("startHour", 8).coerceIn(0, 23),
                        startMinute = item.optInt("startMinute", 0).coerceIn(0, 59),
                        endHour = item.optInt("endHour", 18).coerceIn(0, 23),
                        endMinute = item.optInt("endMinute", 0).coerceIn(0, 59),
                        intervalMinutes = item.optInt("intervalMinutes", 15).coerceAtLeast(1),
                        playbackOrder = item.optString("playbackOrder", "SEQUENTIAL"),
                        specificAudioId = mappedSpecificAudioId,
                        audioIdsCsv = mappedAudioIds,
                        resumePolicy = item.optString("resumePolicy", "NEXT_GRID_MARK"),
                        fadeInOverrideEnabled = item.optBoolean("fadeInOverrideEnabled", false),
                        fadeInDurationSeconds = item.optDouble("fadeInDurationSeconds", 2.0)
                            .toFloat().coerceIn(0.5f, 10.0f)
                    )
                    val rests = mutableListOf<RestingPeriodEntity>()
                    val restsArray = item.optJSONArray("restingPeriods") ?: JSONArray()
                    for (j in 0 until restsArray.length()) {
                        val rest = restsArray.getJSONObject(j)
                        rests += RestingPeriodEntity(
                            label = rest.optString("label", "Descanso"),
                            startHour = rest.optInt("startHour", 12).coerceIn(0, 23),
                            startMinute = rest.optInt("startMinute", 0).coerceIn(0, 59),
                            endHour = rest.optInt("endHour", 13).coerceIn(0, 23),
                            endMinute = rest.optInt("endMinute", 0).coerceIn(0, 59)
                        )
                    }
                    val newId = database.profileDao().upsertProfileWithRests(profile, rests)
                    if (oldId > 0L) profileIdMap[oldId] = newId
                    importedProfiles++
                }

                for (i in 0 until remindersArray.length()) {
                    val item = remindersArray.getJSONObject(i)
                    val oldProfileId = item.optLongOrNull("profileId")
                    val newProfileId = oldProfileId?.let { profileIdMap[it] }
                    val entity = ReminderEntity(
                        id = 0L,
                        profileId = newProfileId,
                        title = item.optString("title", "Lembrete"),
                        text = item.optString("text", ""),
                        audioFilePath = item.optNullableString("audioFilePath"),
                        audioUri = item.optNullableString("audioUri"),
                        intervalMinutes = item.optInt("intervalMinutes", 15).coerceAtLeast(1),
                        isEnabled = item.optBoolean("isEnabled", true),
                        hasRestPeriod = item.optBoolean("hasRestPeriod", false),
                        startHour = item.optInt("startHour", 0).coerceIn(0, 23),
                        startMinute = item.optInt("startMinute", 0).coerceIn(0, 59),
                        endHour = item.optInt("endHour", 23).coerceIn(0, 23),
                        endMinute = item.optInt("endMinute", 59).coerceIn(0, 59),
                        daysOfWeekCsv = item.optString("daysOfWeekCsv", "1,2,3,4,5,6,7"),
                        dateCreated = item.optLong("dateCreated", System.currentTimeMillis())
                    )
                    database.reminderDao().insertReminder(entity)
                    importedReminders++
                }
            }

            // Preferences use DataStore, so apply them after the Room transaction commits.
            if (prefsObject != null) {
                prefsRepo.setAppMasterEnabled(prefsObject.optBoolean("isAppMasterEnabled", false))
                prefsRepo.setMasterVolumePercent(prefsObject.optInt("masterVolumePercent", 100))
                prefsRepo.setUseAppVolumeOnly(prefsObject.optBoolean("useAppVolumeOnly", false))
                prefsRepo.setFadeInEnabled(prefsObject.optBoolean("fadeInEnabled", true))
                prefsRepo.setFadeInDurationSeconds(prefsObject.optDouble("fadeInDurationSeconds", 2.0).toFloat())
                prefsRepo.setVibrateWithAudio(prefsObject.optBoolean("vibrateWithAudio", true))
                prefsRepo.setShowLabelInNotification(prefsObject.optBoolean("showLabelInNotification", true))
                prefsRepo.setRespectDoNotDisturb(prefsObject.optBoolean("respectDoNotDisturb", true))
                prefsRepo.setDefaultResumePolicy(prefsObject.optString("defaultResumePolicy", "NEXT_GRID_MARK"))
                prefsRepo.setQuietHours(
                    enabled = prefsObject.optBoolean("quietHoursEnabled", false),
                    startHour = prefsObject.optInt("quietHoursStartHour", 22),
                    startMinute = prefsObject.optInt("quietHoursStartMinute", 0),
                    endHour = prefsObject.optInt("quietHoursEndHour", 7),
                    endMinute = prefsObject.optInt("quietHoursEndMinute", 0),
                    daysCsv = prefsObject.optString("quietHoursDaysCsv", "1,2,3,4,5,6,7"),
                    notifySilently = prefsObject.optBoolean("quietHoursNotifySilently", false)
                )
            }

            ImportResult(
                success = true,
                message = "Backup restaurado: $importedProfiles perfis, $importedAudios áudios e $importedReminders lembretes importados. Áudios locais ausentes foram desativados.",
                importedProfilesCount = importedProfiles,
                importedAudiosCount = importedAudios
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                message = "Erro ao processar backup: ${e.localizedMessage ?: "formato inválido"}. Nenhuma alteração parcial no banco foi mantida."
            )
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).takeIf { it > 0L }
    }

    private companion object {
        const val BACKUP_VERSION = 2
    }
}
