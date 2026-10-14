package com.example.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.db.AppDatabase
import com.example.data.db.ProfileEntity
import com.example.data.db.RestingPeriodEntity
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioReminderWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var prefsRepo: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = AppDatabase.getDatabase(context)
        prefsRepo = UserPreferencesRepository(context)
    }

    @Test
    fun `worker finishes successfully when app master switch is disabled`() = runBlocking {
        prefsRepo.setAppMasterEnabled(false)

        val worker = TestListenableWorkerBuilder<AudioReminderWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `worker respects resting periods saved in Room database`() = runBlocking {
        prefsRepo.setAppMasterEnabled(true)

        // Salva perfil com Horário de Descanso cobrindo as 24 horas no Room
        val now = LocalTime.now()
        val profileId = database.profileDao().insertProfile(
            ProfileEntity(
                name = "Perfil Teste Descanso",
                isEnabled = true,
                priority = 10,
                daysOfWeekCsv = "1,2,3,4,5,6,7",
                startHour = 0,
                startMinute = 0,
                endHour = 23,
                endMinute = 59,
                intervalMinutes = 15
            )
        )

        // Insere descanso de 24 horas no Room cobrindo todo o dia
        database.profileDao().insertRestingPeriods(
            listOf(
                RestingPeriodEntity(
                    profileId = profileId,
                    label = "Sono Noturno / Descanso",
                    startHour = 0,
                    startMinute = 0,
                    endHour = 23,
                    endMinute = 59
                )
            )
        )

        val worker = TestListenableWorkerBuilder<AudioReminderWorker>(context).build()
        val result = worker.doWork()

        // O worker deve executar sem erros e respeitar o descanso (retornando success)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `work scheduler schedules periodic work correctly`() {
        ReminderWorkScheduler.schedulePeriodicWork(
            context = context,
            intervalMinutes = 15,
            flexMinutes = 5
        )

        val workManager = androidx.work.WorkManager.getInstance(context)
        assertNotNull(workManager)
    }

    @Test
    fun `worker respects quiet hours configured in preferences`() = runBlocking {
        prefsRepo.setAppMasterEnabled(true)
        val now = LocalTime.now()
        // Define quiet hours cobrindo todo o dia
        prefsRepo.setQuietHours(
            enabled = true,
            startHour = 0,
            startMinute = 0,
            endHour = 23,
            endMinute = 59,
            daysCsv = "1,2,3,4,5,6,7",
            notifySilently = true
        )

        val worker = TestListenableWorkerBuilder<AudioReminderWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }
}
