package com.example.data.backup

import android.content.Context
import android.net.Uri
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.RestingPeriodEntity
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

data class ImportResult(
    val success: Boolean,
    val profilesImported: Int = 0,
    val audiosImported: Int = 0,
    val missingAudioLabels: List<String> = emptyList(),
    val errorMessage: String? = null
)

class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val preferencesRepo: UserPreferencesRepository
) {

    suspend fun exportConfig(outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("exportTimestamp", System.currentTimeMillis())

            // Export Preferences
            val prefs = preferencesRepo.getPreferences()
            val prefsObj = JSONObject().apply {
                put("masterVolumePercent", prefs.masterVolumePercent)
                put("useAppVolumeOnly", prefs.useAppVolumeOnly)
                put("fadeInEnabled", prefs.fadeInEnabled)
                put("fadeInDurationSeconds", prefs.fadeInDurationSeconds.toDouble())
                put("vibrateWithAudio", prefs.vibrateWithAudio)
                put("showLabelInNotification", prefs.showLabelInNotification)
                put("respectDoNotDisturb", prefs.respectDoNotDisturb)
                put("defaultResumePolicy", prefs.defaultResumePolicy)
            }
            root.put("preferences", prefsObj)

            // Export Audios metadata
            val audios = database.audioDao().getAllAudios()
            val audiosArray = JSONArray()
            for (a in audios) {
                val aObj = JSONObject().apply {
                    put("id", a.id)
                    put("uriString", a.uriString)
                    put("label", a.label)
                    put("weight", a.weight)
                    put("volumePercent", a.volumePercent)
                    put("isActive", a.isActive)
                    put("audioType", a.audioType)
                    put("ttsText", a.ttsText ?: "")
                }
                audiosArray.put(aObj)
            }
            root.put("audios", audiosArray)

            // Export Profiles and Resting Periods
            val profilesWithRests = database.profileDao().getAllProfilesWithRests()
            val profilesArray = JSONArray()
            for (p in profilesWithRests) {
                val pObj = JSONObject().apply {
                    put("name", p.profile.name)
                    put("isEnabled", p.profile.isEnabled)
                    put("priority", p.profile.priority)
                    put("daysOfWeekCsv", p.profile.daysOfWeekCsv)
                    put("startHour", p.profile.startHour)
                    put("startMinute", p.profile.startMinute)
                    put("endHour", p.profile.endHour)
                    put("endMinute", p.profile.endMinute)
                    put("intervalMinutes", p.profile.intervalMinutes)
                    put("playbackOrder", p.profile.playbackOrder)
                    put("audioIdsCsv", p.profile.audioIdsCsv)
                    put("resumePolicy", p.profile.resumePolicy)
                    put("fadeInOverrideEnabled", p.profile.fadeInOverrideEnabled)
                    put("fadeInDurationSeconds", p.profile.fadeInDurationSeconds.toDouble())

                    val restsArray = JSONArray()
                    for (r in p.rests) {
                        val rObj = JSONObject().apply {
                            put("label", r.label)
                            put("startHour", r.startHour)
                            put("startMinute", r.startMinute)
                            put("endHour", r.endHour)
                            put("endMinute", r.endMinute)
                        }
                        restsArray.put(rObj)
                    }
                    put("restingPeriods", restsArray)
                }
                profilesArray.put(pObj)
            }
            root.put("profiles", profilesArray)

            outputStream.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importConfig(inputStream: InputStream): ImportResult = withContext(Dispatchers.IO) {
        try {
            val jsonString = inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            val root = JSONObject(jsonString)

            if (!root.has("profiles") || !root.has("audios")) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage = "Arquivo inválido: campos obrigatórios ausentes."
                )
            }

            // Restore preferences if present
            if (root.has("preferences")) {
                val p = root.getJSONObject("preferences")
                preferencesRepo.setMasterVolumePercent(p.optInt("masterVolumePercent", 100))
                preferencesRepo.setUseAppVolumeOnly(p.optBoolean("useAppVolumeOnly", false))
                preferencesRepo.setFadeInEnabled(p.optBoolean("fadeInEnabled", true))
                preferencesRepo.setFadeInDurationSeconds(p.optDouble("fadeInDurationSeconds", 2.0).toFloat())
                preferencesRepo.setVibrateWithAudio(p.optBoolean("vibrateWithAudio", true))
                preferencesRepo.setShowLabelInNotification(p.optBoolean("showLabelInNotification", true))
                preferencesRepo.setRespectDoNotDisturb(p.optBoolean("respectDoNotDisturb", true))
                preferencesRepo.setDefaultResumePolicy(p.optString("defaultResumePolicy", "NEXT_GRID_MARK"))
            }

            // Import audios
            val missingLabels = mutableListOf<String>()
            val audiosArray = root.getJSONArray("audios")
            var audiosCount = 0

            for (i in 0 until audiosArray.length()) {
                val aObj = audiosArray.getJSONObject(i)
                val uriStr = aObj.getString("uriString")
                val label = aObj.getString("label")
                val weight = aObj.optInt("weight", 3)
                val vol = aObj.optInt("volumePercent", 100)
                val active = aObj.optBoolean("isActive", true)

                // Check if URI is accessible
                var isAccessible = false
                try {
                    val uri = Uri.parse(uriStr)
                    context.contentResolver.openInputStream(uri)?.use { isAccessible = true }
                } catch (_: Exception) {
                    isAccessible = false
                }

                if (!isAccessible) {
                    missingLabels.add(label)
                }

                val entity = AudioItemEntity(
                    uriString = uriStr,
                    label = label,
                    weight = weight,
                    volumePercent = vol,
                    isActive = active,
                    audioType = aObj.optString("audioType", "AUDIO_FILE"),
                    ttsText = if (aObj.has("ttsText") && aObj.getString("ttsText").isNotBlank()) aObj.getString("ttsText") else null
                )
                database.audioDao().insertAudio(entity)
                audiosCount++
            }

            // Import profiles
            val profilesArray = root.getJSONArray("profiles")
            var profilesCount = 0

            for (i in 0 until profilesArray.length()) {
                val pObj = profilesArray.getJSONObject(i)
                val profile = ProfileEntity(
                    name = pObj.getString("name"),
                    isEnabled = pObj.optBoolean("isEnabled", true),
                    priority = pObj.optInt("priority", 0),
                    daysOfWeekCsv = pObj.optString("daysOfWeekCsv", "1,2,3,4,5"),
                    startHour = pObj.optInt("startHour", 8),
                    startMinute = pObj.optInt("startMinute", 0),
                    endHour = pObj.optInt("endHour", 22),
                    endMinute = pObj.optInt("endMinute", 0),
                    intervalMinutes = pObj.optInt("intervalMinutes", 15),
                    playbackOrder = pObj.optString("playbackOrder", "SEQUENTIAL"),
                    audioIdsCsv = pObj.optString("audioIdsCsv", ""),
                    resumePolicy = pObj.optString("resumePolicy", "NEXT_GRID_MARK"),
                    fadeInOverrideEnabled = pObj.optBoolean("fadeInOverrideEnabled", false),
                    fadeInDurationSeconds = pObj.optDouble("fadeInDurationSeconds", 2.0).toFloat()
                )

                val rests = mutableListOf<RestingPeriodEntity>()
                val restsArray = pObj.optJSONArray("restingPeriods")
                if (restsArray != null) {
                    for (r in 0 until restsArray.length()) {
                        val rObj = restsArray.getJSONObject(r)
                        rests.add(
                            RestingPeriodEntity(
                                profileId = 0L,
                                label = rObj.optString("label", ""),
                                startHour = rObj.optInt("startHour", 12),
                                startMinute = rObj.optInt("startMinute", 0),
                                endHour = rObj.optInt("endHour", 13),
                                endMinute = rObj.optInt("endMinute", 0)
                            )
                        )
                    }
                }

                database.profileDao().upsertProfileWithRests(profile, rests)
                profilesCount++
            }

            ImportResult(
                success = true,
                profilesImported = profilesCount,
                audiosImported = audiosCount,
                missingAudioLabels = missingLabels
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                errorMessage = "Falha ao importar: ${e.localizedMessage ?: "Erro desconhecido"}"
            )
        }
    }
}
