package com.localyze

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.localyze.ai.ModelInitializer
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.DownloadProgress
import com.localyze.data.repository.ModelRepository
import com.localyze.ui.viewmodels.OnboardingUiState
import com.localyze.ui.viewmodels.OnboardingViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingDownloadRetryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var modelRepository: ModelRepository
    private lateinit var modelInitializer: ModelInitializer
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var logMock: MockedStatic<Log>

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)

        logMock = Mockito.mockStatic(Log::class.java)
        logMock.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        logMock.`when`<Int> { Log.d(any<String>(), any<String>(), any()) }.thenReturn(0)
        logMock.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        logMock.`when`<Int> { Log.e(any<String>(), any<String>(), any()) }.thenReturn(0)

        modelRepository = mock()
        modelInitializer = mock()
        settingsDataStore = mock()

        whenever(modelRepository.isModelDownloaded()).thenReturn(false)
        whenever(modelRepository.getModelFilePath()).thenReturn("/data/user/0/com.localyze/files/models/gemma-4-E4B-it.litertlm")
        whenever(modelRepository.getModelFileSize()).thenReturn(0L)
        whenever(modelRepository.isTestModelFile()).thenReturn(false)
    }

    @After
    fun tearDown() {
        logMock.close()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun retryDownload_resumesPartialDownloadWithoutDeletingTempFile() = testScope.runTest {
        val partialBytes = 5_953_322L
        val totalBytes = ModelRepository.MODEL_SIZE_BYTES

        whenever(modelRepository.canResumeDownload()).thenReturn(true)
        whenever(modelRepository.downloadModel(eq(true))).thenReturn(
            flowOf(DownloadProgress.Resuming(partialBytes, totalBytes))
        )

        val viewModel = OnboardingViewModel(
            modelRepository = modelRepository,
            modelInitializer = modelInitializer,
            settingsDataStore = settingsDataStore,
            savedStateHandle = SavedStateHandle()
        )

        viewModel.retryDownload()
        advanceUntilIdle()

        verify(modelRepository, never()).deleteModel()
        verify(modelRepository).downloadModel(eq(true))

        val state = viewModel.uiState.value
        assertTrue(state is OnboardingUiState.Downloading)
        val progress = (state as OnboardingUiState.Downloading).progress
        assertTrue(progress is DownloadProgress.Downloading)
        assertEquals(partialBytes, (progress as DownloadProgress.Downloading).bytesDownloaded)
    }
}
