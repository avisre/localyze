package com.localyze.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localyze.ai.ModelInitializer
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.DownloadProgress
import com.localyze.data.repository.ModelEntry
import com.localyze.data.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val modelInitializer: ModelInitializer,
    private val settingsDataStore: SettingsDataStore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Welcome)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        // Check if model is already downloaded on init
        checkIfModelAlreadyDownloaded()
    }

    /**
     * Checks if the model is already downloaded. If so, initialize it and skip to ReadyToChat.
     * This was the critical bug: on app restart, the model file existed but was never
     * loaded into memory, so every query fell back to hardcoded responses.
     */
    private fun checkIfModelAlreadyDownloaded() {
        com.localyze.utils.AppLog.d("OnboardingVM", "checkIfModelAlreadyDownloaded: isModelDownloaded=${modelRepository.isModelDownloaded()}")
        com.localyze.utils.AppLog.d("OnboardingVM", "checkIfModelAlreadyDownloaded: modelPath=${modelRepository.getModelFilePath()}")
        com.localyze.utils.AppLog.d("OnboardingVM", "checkIfModelAlreadyDownloaded: modelSize=${modelRepository.getModelFileSize()}")
        com.localyze.utils.AppLog.d("OnboardingVM", "checkIfModelAlreadyDownloaded: isTestModel=${modelRepository.isTestModelFile()}")
        com.localyze.utils.AppLog.d("OnboardingVM", "checkIfModelAlreadyDownloaded: USE_TEST_DOWNLOAD=${com.localyze.BuildConfig.USE_TEST_DOWNLOAD}")
        if (modelRepository.isModelDownloaded()) {
            // Model file exists â€” load it into memory before showing the chat
            com.localyze.utils.AppLog.d("OnboardingVM", "Model already downloaded, initializing...")
            initializeModel()
        } else {
            com.localyze.utils.AppLog.d("OnboardingVM", "Model NOT downloaded, showing onboarding")
        }
    }

    /**
     * Checks device prerequisites (RAM and storage) before allowing download.
     * Transitions from Welcome to the appropriate state.
     */
    fun checkPrerequisites() {
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.CheckingModel(isChecking = true)

            // Check RAM first
            if (!modelRepository.hasMinimumRam()) {
                _uiState.value = OnboardingUiState.InsufficientRam
                return@launch
            }

            // Check storage
            if (!modelRepository.hasEnoughStorage()) {
                _uiState.value = OnboardingUiState.InsufficientStorage
                return@launch
            }

            // All prerequisites met, show model selection
            val models = modelRepository.getAllModels()
            val selectedModel = modelRepository.getSelectedModel()
            _uiState.value = OnboardingUiState.ModelSelection(
                models = models,
                selectedModel = selectedModel
            )
        }
    }

    /**
     * Continues past the RAM warning. User acknowledges the risk.
     */
    fun continueWithInsufficientRam() {
        // Still check storage
        if (!modelRepository.hasEnoughStorage()) {
            _uiState.value = OnboardingUiState.InsufficientStorage
        } else {
            val selectedModel = modelRepository.getSelectedModel()
            _uiState.value = OnboardingUiState.ReadyToDownload(selectedModel)
        }
    }

    /**
     * Checks if download can proceed based on network type and user preferences.
     * Shows warning if on cellular without permission.
     */
    fun checkNetworkAndStartDownload() {
        viewModelScope.launch {
            val allowCellular = settingsDataStore.allowCellularDownload.first()

            if (modelRepository.shouldAllowDownload(allowCellular)) {
                startDownload()
            } else {
                // Show network warning with dynamic model size
                val networkStatus = modelRepository.getNetworkStatus()
                val selectedModel = modelRepository.getSelectedModel()
                val sizeGB = String.format("%.1f", selectedModel.sizeBytes / (1024.0 * 1024.0 * 1024.0))
                _uiState.value = OnboardingUiState.NetworkWarning(
                    networkType = networkStatus,
                    dataSize = "~$sizeGB GB"
                )
            }
        }
    }

    /**
     * User confirms they want to download over cellular.
     */
    fun confirmCellularDownload() {
        viewModelScope.launch {
            // Save preference for future
            settingsDataStore.setAllowCellularDownload(true)
            startDownload()
        }
    }

    /**
     * Selects a model and transitions to ReadyToDownload.
     */
    fun selectModel(model: ModelEntry) {
        modelRepository.setSelectedModel(model)
        _uiState.value = OnboardingUiState.ReadyToDownload(model)
    }

    /**
     * Starts the model download process for the currently selected model.
     */
    fun startDownload() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            // Check if we can resume a partial download
            val canResume = modelRepository.canResumeDownload()
            modelRepository.downloadModel(resume = canResume).collect { progress ->
                when (progress) {
                    is DownloadProgress.Downloading -> {
                        _uiState.value = OnboardingUiState.Downloading(progress)
                    }
                    is DownloadProgress.Resuming -> {
                        // Show resuming state as downloading with existing progress
                        _uiState.value = OnboardingUiState.Downloading(
                            DownloadProgress.Downloading(
                                bytesDownloaded = progress.bytesAlreadyDownloaded,
                                totalBytes = progress.totalBytes,
                                percent = progress.bytesAlreadyDownloaded.toFloat() / progress.totalBytes,
                                estimatedSecondsRemaining = 0
                            )
                        )
                    }
                    is DownloadProgress.Verifying -> {
                        _uiState.value = OnboardingUiState.Verifying(progress.percent)
                    }
                    is DownloadProgress.Complete -> {
                        initializeModel()
                    }
                    is DownloadProgress.Error -> {
                        _uiState.value = OnboardingUiState.Error(
                            message = progress.message,
                            isRetryable = progress.isRetryable
                        )
                    }
                }
            }
        }
    }

    /**
     * Retries the download and resumes any preserved partial file.
     */
    fun retryDownload() {
        startDownload()
    }

    /**
     * Initializes the model after download completes.
     */
    private fun initializeModel() {
        viewModelScope.launch {
            try {
                // If this is a test model (small file for debug), skip actual model initialization
                // Mock mode has been removed — real engine only
                if (modelRepository.isTestModelFile() || com.localyze.BuildConfig.USE_TEST_DOWNLOAD) {
                    com.localyze.utils.AppLog.d("OnboardingVM", "Test model detected, skipping real init")
                    _uiState.value = OnboardingUiState.ReadyToChat
                    return@launch
                }
                com.localyze.utils.AppLog.d("OnboardingVM", "Starting real model initialization...")
                _uiState.value = OnboardingUiState.CheckingModel(isChecking = true)
                modelInitializer.initialize()
                com.localyze.utils.AppLog.d("OnboardingVM", "Model initialization SUCCESS")
                _uiState.value = OnboardingUiState.ReadyToChat
            } catch (e: Exception) {
                android.util.Log.e("OnboardingVM", "Model initialization FAILED: ${e.message}", e)
                _uiState.value = OnboardingUiState.Error(
                    message = "Model downloaded but failed to initialize: ${e.message}",
                    isRetryable = true
                )
            }
        }
    }

    /**
     * Cancels the current download.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        modelRepository.deleteModel()
        val selectedModel = modelRepository.getSelectedModel()
        _uiState.value = OnboardingUiState.ReadyToDownload(selectedModel)
    }

    /**
     * Navigates back to the welcome screen from error or prerequisite states.
     */
    fun navigateBack() {
        _uiState.value = OnboardingUiState.Welcome
    }

    /**
     * Resets to ready-to-download state (e.g., after dismissing a storage error).
     */
    fun dismissStorageError() {
        _uiState.value = OnboardingUiState.Welcome
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
    }
}
