package com.example.data.backup

data class ImportResult(
    val success: Boolean,
    val message: String = "",
    val profilesImported: Int = 0,
    val audiosImported: Int = 0,
    val missingAudioLabels: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    val importedProfilesCount: Int get() = profilesImported
    val importedAudiosCount: Int get() = audiosImported

    constructor(
        success: Boolean,
        message: String,
        importedProfilesCount: Int,
        importedAudiosCount: Int
    ) : this(
        success = success,
        message = message,
        profilesImported = importedProfilesCount,
        audiosImported = importedAudiosCount,
        missingAudioLabels = emptyList(),
        errorMessage = null
    )
}
