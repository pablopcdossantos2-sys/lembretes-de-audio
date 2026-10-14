package com.example.data.backup

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.AudioItemEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.RestingPeriodEntity
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
            root.put("version", 1)
            root.put("exportTimestamp", System.currentTimeMillis())

            // Export Preferences
            val prefs = prefsRepo.getPreferences()
            val prefsObj = JSONObject().apply {
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
            }
            root.put("preferences", prefsObj)

            // Export Profiles
            val profilesWithRests = database.profileDao().getAllProfilesWithRests()
            val profilesArray = JSONArray()
            for (item in profilesWithRests) {
                val p = item.profile
                val pObj = JSONObject().apply {
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
                    put("resumePolicy", p.resumePolicy)
                    put("audioIdsCsv", p.audioIdsCsv)
                    put("fadeInOverrideEnabled", p.fadeInOverrideEnabled)
                    put("fadeInDurationSeconds", p.fadeInDurationSeconds.toDouble())

                    val restsArray = JSONArray()
                    for (r in item.restingPeriods) {
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

            // Export Audios
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
                    put("dateAdded", a.dateAdded)
                }
                audiosArray.put(aObj)
            }
            root.put("audios", audiosArray)

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
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)

            var importedProfiles = 0
            var importedAudios = 0

            // Import Preferences if present
            if (root.has("preferences")) {
                val p = root.getJSONObject("preferences")
                prefsRepo.setMasterVolumePercent(p.optInt("masterVolumePercent", 100))
                prefsRepo.setUseAppVolumeOnly(p.optBoolean("useAppVolumeOnly", false))
                prefsRepo.setFadeInEnabled(p.optBoolean("fadeInEnabled", true))
                prefsRepo.setFadeInDurationSeconds(p.optDouble("fadeInDurationSeconds", 2.0).toFloat())
                prefsRepo.setVibrateWithAudio(p.optBoolean("vibrateWithAudio", true))
                prefsRepo.setShowLabelInNotification(p.optBoolean("showLabelInNotification", true))
                prefsRepo.setRespectDoNotDisturb(p.optBoolean("respectDoNotDisturb", true))
                prefsRepo.setDefaultResumePolicy(p.optString("defaultResumePolicy", "NEXT_GRID_MARK"))
                prefsRepo.setQuietHoursEnabled(p.optBoolean("quietHoursEnabled", true))
                prefsRepo.setQuietHoursStart(
                    p.optInt("quietHoursStartHour", 22),
                    p.optInt("quietHoursStartMinute", 0)
                )
                prefsRepo.setQuietHoursEnd(
                    p.optInt("quietHoursEndHour", 7),
                    p.optInt("quietHoursEndMinute", 0)
                )
                prefsRepo.setQuietHoursDaysCsv(p.optString("quietHoursDaysCsv", "1,2,3,4,5,6,7"))
                prefsRepo.setQuietHoursNotifySilently(p.optBoolean("quietHoursNotifySilently", true))
            }

            // Import Profiles
            if (root.has("profiles")) {
                val profilesArray = root.getJSONArray("profiles")
                for (i in 0 until profilesArray.length()) {
                    val pObj = profilesArray.getJSONObject(i)
                    val profileEntity = ProfileEntity(
                        id = 0L, // new auto-generated ID
                        name = pObj.optString("name", "Perfil Importado"),
                        isEnabled = pObj.optBoolean("isEnabled", true),
                        priority = pObj.optInt("priority", 1),
                        daysOfWeekCsv = pObj.optString("daysOfWeekCsv", "1,2,3,4,5,6,7"),
                        startHour = pObj.optInt("startHour", 8),
                        startMinute = pObj.optInt("startMinute", 0),
                        endHour = pObj.optInt("endHour", 18),
                        endMinute = pObj.optInt("endMinute", 0),
                        intervalMinutes = pObj.optInt("intervalMinutes", 15),
                        playbackOrder = pObj.optString("playbackOrder", "SEQUENTIAL"),
                        resumePolicy = pObj.optString("resumePolicy", "NEXT_GRID_MARK"),
                        audioIdsCsv = pObj.optString("audioIdsCsv", ""),
                        fadeInOverrideEnabled = pObj.optBoolean("fadeInOverrideEnabled", false),
                        fadeInDurationSeconds = pObj.optDouble("fadeInDurationSeconds", 2.0).toFloat()
                    )

                    val restingPeriods = mutableListOf<RestingPeriodEntity>()
                    if (pObj.has("restingPeriods")) {
                        val restsArray = pObj.getJSONArray("restingPeriods")
                        for (j in 0 until restsArray.length()) {
                            val rObj = restsArray.getJSONObject(j)
                            restingPeriods.add(
                                RestingPeriodEntity(
                                    label = rObj.optString("label", "Descanso"),
                                    startHour = rObj.optInt("startHour", 12),
                                    startMinute = rObj.optInt("startMinute", 0),
                                    endHour = rObj.optInt("endHour", 13),
                                    endMinute = rObj.optInt("endMinute", 0)
                                )
                            )
                        }
                    }

                    database.profileDao().upsertProfileWithRests(profileEntity, restingPeriods)
                    importedProfiles++
                }
            }

            // Import Audios
            if (root.has("audios")) {
                val audiosArray = root.getJSONArray("audios")
                for (i in 0 until audiosArray.length()) {
                    val aObj = audiosArray.getJSONObject(i)
                    val audioEntity = AudioItemEntity(
                        id = 0L,
                        uriString = aObj.optString("uriString", ""),
                        label = aObj.optString("label", "Áudio"),
                        weight = aObj.optInt("weight", 3),
                        volumePercent = aObj.optInt("volumePercent", 100),
                        isActive = aObj.optBoolean("isActive", true),
                        audioType = aObj.optString("audioType", "AUDIO_FILE"),
                        ttsText = if (aObj.has("ttsText") && aObj.getString("ttsText").isNotBlank()) aObj.getString("ttsText") else null,
                        dateAdded = System.currentTimeMillis()
                    )
                    database.audioDao().insertAudio(audioEntity)
                    importedAudios++
                }
            }

            ImportResult(
                success = true,
                message = "Backup restaurado com sucesso! $importedProfiles perfis e $importedAudios áudios importados.",
                importedProfilesCount = importedProfiles,
                importedAudiosCount = importedAudios
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                message = "Erro ao processar arquivo de backup: ${e.localizedMessage ?: "Formato inválido"}"
            )
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }
}
