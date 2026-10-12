package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.db.LembreteDao
import com.example.data.db.LembreteEntity
import com.example.data.db.ReminderEntity
import com.example.ui.viewmodel.LembreteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeLembreteDao : LembreteDao {
    private val remindersMap = mutableMapOf<Long, ReminderEntity>()
    private val flow = MutableStateFlow<List<ReminderEntity>>(emptyList())
    private var currentId = 1L

    private fun updateFlow() {
        flow.value = remindersMap.values.toList().sortedByDescending { it.createdAt }
    }

    override fun getAllRemindersFlow(): Flow<List<ReminderEntity>> = flow

    override suspend fun getAllReminders(): List<ReminderEntity> = flow.value

    override fun getActiveRemindersFlow(): Flow<List<ReminderEntity>> = flow

    override suspend fun getActiveReminders(): List<ReminderEntity> = flow.value.filter { it.isEnabled }

    override suspend fun getReminderById(id: Long): ReminderEntity? = remindersMap[id]

    override suspend fun insertReminder(reminder: ReminderEntity): Long {
        val id = if (reminder.id == 0L) currentId++ else reminder.id
        val entity = reminder.copy(id = id)
        remindersMap[id] = entity
        updateFlow()
        return id
    }

    override suspend fun insertReminders(reminders: List<ReminderEntity>): List<Long> {
        return reminders.map { insertReminder(it) }
    }

    override suspend fun updateReminder(reminder: ReminderEntity) {
        remindersMap[reminder.id] = reminder
        updateFlow()
    }

    override suspend fun deleteReminder(reminder: ReminderEntity) {
        remindersMap.remove(reminder.id)
        updateFlow()
    }

    override suspend fun deleteReminderById(id: Long) {
        remindersMap.remove(id)
        updateFlow()
    }

    override suspend fun setReminderEnabled(id: Long, isEnabled: Boolean) {
        remindersMap[id]?.let {
            remindersMap[id] = it.copy(isEnabled = isEnabled)
            updateFlow()
        }
    }

    override suspend fun clearAllReminders() {
        remindersMap.clear()
        updateFlow()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LembreteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeLembreteDao
    private lateinit var viewModel: LembreteViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeLembreteDao()
        val app: Application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        viewModel = LembreteViewModel(app, fakeDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAdicionarEListarLembrete() = runTest(testDispatcher) {
        val lembrete = LembreteEntity(
            title = "Estudar Kotlin",
            text = "Revisar Coroutines e Flow",
            periodicityMinutes = 30
        )

        viewModel.adicionarLembrete(lembrete)
        testDispatcher.scheduler.advanceUntilIdle()

        val lista = fakeDao.getAllReminders()
        assertEquals(1, lista.size)
        assertEquals("Estudar Kotlin", lista[0].title)
        assertEquals(30, lista[0].periodicityMinutes)
    }

    @Test
    fun testDeletarLembrete() = runTest(testDispatcher) {
        val id = fakeDao.insertReminder(
            LembreteEntity(
                title = "Beber água",
                periodicityMinutes = 15
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fakeDao.getAllReminders().size)

        viewModel.deletarLembretePorId(id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fakeDao.getAllReminders().size)
    }

    @Test
    fun testAudioPlayerEstadoInicial() {
        assertEquals(null, viewModel.currentlyPlayingReminderId.value)
        assertEquals(false, viewModel.isPlaying.value)
        assertEquals(0, viewModel.currentPositionMs.value)
        assertEquals(0, viewModel.durationMs.value)
    }

    @Test
    fun testPararAudioRedefineEstado() {
        viewModel.pararAudio()
        assertEquals(null, viewModel.currentlyPlayingReminderId.value)
        assertEquals(false, viewModel.isPlaying.value)
        assertEquals(0, viewModel.currentPositionMs.value)
        assertEquals(0, viewModel.durationMs.value)
    }

    @Test
    fun testAlternarReproducaoSemAudioEmiteMensagem() = runTest(testDispatcher) {
        val lembreteSemAudio = LembreteEntity(
            title = "Lembrete sem áudio",
            audioUri = null
        )

        viewModel.alternarReproducao(lembreteSemAudio)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.currentlyPlayingReminderId.value)
        assertEquals(false, viewModel.isPlaying.value)
    }
}
