package com.localassistant.ui.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.ai.GemmaInferenceEngine
import com.localassistant.data.local.SettingsDataStore
import com.localassistant.data.repository.MemoryRepositoryImpl
import com.localassistant.data.repository.ModelRepository
import com.localassistant.domain.models.Memory
import com.localassistant.domain.usecases.ManageMemoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val memoryRepository: MemoryRepositoryImpl,
    private val modelRepository: ModelRepository,
    private val gemmaInferenceEngine: GemmaInferenceEngine,
    private val manageMemoryUseCase: ManageMemoryUseCase,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val memoriesFlow = memoryRepository.getAllMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { settingsDataStore.darkMode.collect { _uiState.update { s -> s.copy(darkMode = it) } } }
        viewModelScope.launch { settingsDataStore.thinkingMode.collect { _uiState.update { s -> s.copy(thinkingMode = it) } } }
        viewModelScope.launch { settingsDataStore.streamTokens.collect { _uiState.update { s -> s.copy(streamTokens = it) } } }
        viewModelScope.launch { settingsDataStore.voiceAutoPlay.collect { _uiState.update { s -> s.copy(voiceAutoPlay = it) } } }
        viewModelScope.launch { settingsDataStore.allowWebSearch.collect { _uiState.update { s -> s.copy(allowWebSearch = it) } } }
        viewModelScope.launch { settingsDataStore.allowCellularDownload.collect { _uiState.update { s -> s.copy(allowCellularDownload = it) } } }

        viewModelScope.launch {
            memoriesFlow.collect { memories ->
                _uiState.update { it.copy(memories = memories, memoryCount = memories.size) }
            }
        }

        refreshModelInfo()
        refreshStorageInfo()
    }

    fun toggleDarkMode() {
        val newValue = !_uiState.value.darkMode
        viewModelScope.launch { settingsDataStore.setDarkMode(newValue) }
        _uiState.update { it.copy(darkMode = newValue) }
    }

    fun toggleThinkingMode() {
        val newValue = !_uiState.value.thinkingMode
        viewModelScope.launch { settingsDataStore.setThinkingMode(newValue) }
        _uiState.update { it.copy(thinkingMode = newValue) }
    }

    fun toggleStreamTokens() {
        val newValue = !_uiState.value.streamTokens
        viewModelScope.launch { settingsDataStore.setStreamTokens(newValue) }
        _uiState.update { it.copy(streamTokens = newValue) }
    }

    fun toggleVoiceAutoPlay() {
        val newValue = !_uiState.value.voiceAutoPlay
        viewModelScope.launch { settingsDataStore.setVoiceAutoPlay(newValue) }
        _uiState.update { it.copy(voiceAutoPlay = newValue) }
    }

    fun toggleAllowWebSearch() {
        val newValue = !_uiState.value.allowWebSearch
        viewModelScope.launch { settingsDataStore.setAllowWebSearch(newValue) }
        _uiState.update { it.copy(allowWebSearch = newValue) }
    }

    fun toggleAllowCellularDownload() {
        val newValue = !_uiState.value.allowCellularDownload
        viewModelScope.launch { settingsDataStore.setAllowCellularDownload(newValue) }
        _uiState.update { it.copy(allowCellularDownload = newValue) }
    }

    fun searchMemories(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                _uiState.update { it.copy(searchResults = emptyList(), memorySearchQuery = query) }
            } else {
                val results = manageMemoryUseCase.searchMemories(query)
                _uiState.update { it.copy(searchResults = results, memorySearchQuery = query) }
            }
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.deleteMemory(id) }
    }

    fun updateMemory(memory: Memory, content: String, keywordsCsv: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val keywords = keywordsCsv.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            memoryRepository.updateMemory(memory.copy(content = content.trim(), keywords = keywords))
        }
    }

    fun refreshMemoryTransparencyText() {
        viewModelScope.launch {
            val memories = memoryRepository.getAllMemories()
            val text = if (memories.isEmpty()) {
                "No long-term memories are saved."
            } else {
                memories.joinToString("\n") { memory ->
                    "- ${memory.content.take(180)}"
                }
            }
            _uiState.update { it.copy(memoryTransparencyText = text) }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            val allMemories = memoryRepository.getAllMemories()
            for (memory in allMemories) { memoryRepository.deleteMemory(memory.id) }
            _uiState.update { it.copy(showClearMemoriesDialog = false) }
        }
    }

    fun toggleMemorySection() {
        _uiState.update { it.copy(isMemorySectionExpanded = !_uiState.value.isMemorySectionExpanded) }
    }

    fun updateMemorySearchQuery(query: String) {
        _uiState.update { it.copy(memorySearchQuery = query) }
        searchMemories(query)
    }

    fun showClearMemoriesDialog() { _uiState.update { it.copy(showClearMemoriesDialog = true) } }
    fun dismissClearMemoriesDialog() { _uiState.update { it.copy(showClearMemoriesDialog = false) } }

    private fun refreshModelInfo() {
        val isDownloaded = modelRepository.isModelDownloaded()
        val modelSizeBytes = modelRepository.getModelFileSize()
        val modelSizeMb = if (modelSizeBytes > 0) modelSizeBytes / (1024 * 1024) else 0L
        val isLoaded = gemmaInferenceEngine.isModelLoaded()
        _uiState.update { it.copy(modelInfo = ModelInfo(isLoaded = isLoaded, isDownloaded = isDownloaded, modelSizeMb = modelSizeMb)) }
    }

    private fun refreshStorageInfo() {
        val availableBytes = modelRepository.getAvailableStorage()
        val availableGb = availableBytes.toFloat() / (1024f * 1024f * 1024f)
        val modelSizeBytes = modelRepository.getModelFileSize()
        val modelSizeMb = if (modelSizeBytes > 0) modelSizeBytes / (1024 * 1024) else 0L
        val memoryCount = _uiState.value.memoryCount
        val estimatedDbSizeMb = memoryCount * 0.01f
        val totalUsedMb = modelSizeMb.toFloat() + estimatedDbSizeMb
        _uiState.update { it.copy(storageInfo = StorageInfo(availableStorageGb = availableGb, modelSizeMb = modelSizeMb, databaseSizeMb = estimatedDbSizeMb, totalUsedMb = totalUsedMb)) }
    }

    fun showDeleteModelDialog() { _uiState.update { it.copy(showDeleteModelDialog = true) } }
    fun dismissDeleteModelDialog() { _uiState.update { it.copy(showDeleteModelDialog = false) } }

    fun deleteModel() {
        gemmaInferenceEngine.release()
        modelRepository.deleteModel()
        _uiState.update { it.copy(showDeleteModelDialog = false) }
        refreshModelInfo()
        refreshStorageInfo()
    }

    fun setTemperature(value: Float) { gemmaInferenceEngine.setTemperature(value) }
    fun setTopK(value: Int) { gemmaInferenceEngine.setTopK(value) }
}
