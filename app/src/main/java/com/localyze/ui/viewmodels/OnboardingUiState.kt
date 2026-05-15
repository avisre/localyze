package com.localyze.ui.viewmodels

import com.localyze.data.repository.DownloadProgress
import com.localyze.data.repository.ModelEntry

sealed class OnboardingUiState {
    data object Welcome : OnboardingUiState()
    data class CheckingModel(val isChecking: Boolean = true) : OnboardingUiState()
    data class ModelSelection(
        val models: List<ModelEntry> = emptyList(),
        val selectedModel: ModelEntry? = null
    ) : OnboardingUiState()
    data class ReadyToDownload(val selectedModel: ModelEntry) : OnboardingUiState()
    data class Downloading(val progress: DownloadProgress) : OnboardingUiState()
    data class Verifying(val percent: Float) : OnboardingUiState()
    data object ReadyToChat : OnboardingUiState()
    data class Error(val message: String, val isRetryable: Boolean) : OnboardingUiState()
    data object InsufficientRam : OnboardingUiState()
    data object InsufficientStorage : OnboardingUiState()
    data class NetworkWarning(
        val networkType: String,
        val dataSize: String = "~3.6 GB"
    ) : OnboardingUiState()
}