package com.example.data.backup

data class ImportResult(
    val success: Boolean,
    val message: String,
    val importedProfilesCount: Int = 0,
    val importedAudiosCount: Int = 0
)
