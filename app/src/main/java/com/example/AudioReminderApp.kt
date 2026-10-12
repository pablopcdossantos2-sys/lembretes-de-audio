package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.preferences.UserPreferencesRepository

class AudioReminderApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Inicializa o agendamento em segundo plano via WorkManager se o app estiver ativo
        com.example.worker.ReminderWorkScheduler.scheduleWorkBasedOnActiveSettings(this)
    }
}
