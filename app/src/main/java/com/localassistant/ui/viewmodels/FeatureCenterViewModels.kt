package com.localassistant.ui.viewmodels

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.ai.GemmaInferenceEngine
import com.localassistant.ai.ModelLoadState
import com.localassistant.data.local.SettingsDataStore
import com.localassistant.data.repository.AttachmentMemoryRepository
import com.localassistant.data.repository.BackupRepository
import com.localassistant.data.repository.ModelRepository
import com.localassistant.data.repository.PerformanceMonitor
import com.localassistant.data.repository.PerformanceSnapshot
import com.localassistant.data.repository.ReplyDraftRepository
import com.localassistant.data.repository.TaskRepository
import com.localassistant.data.repository.ToolAuditRepository
import com.localassistant.domain.models.AttachmentMemory
import com.localassistant.domain.models.ReplyDraft
import com.localassistant.domain.models.Task
import com.localassistant.domain.models.ToolAudit
import com.localassistant.tools.ToolDispatcher
import com.localassistant.tools.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolCenterUiState(
    val tools: List<ToolCenterItem> = emptyList(),
    val audits: List<ToolAudit> = emptyList(),
    val error: String? = null
)

data class ToolCenterItem(
    val name: String,
    val description: String,
    val riskLevel: String,
    val requiresConfirmation: Boolean
)

@HiltViewModel
class ToolCenterViewModel @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolDispatcher: ToolDispatcher,
    private val toolAuditRepository: ToolAuditRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ToolCenterUiState())
    val uiState: StateFlow<ToolCenterUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                tools = toolRegistry.getAllTools()
                    .sortedBy { tool -> tool.name }
                    .map { tool ->
                        ToolCenterItem(
                            name = tool.name,
                            description = tool.description,
                            riskLevel = toolDispatcher.riskLevel(tool.name),
                            requiresConfirmation = tool.requiresConfirmation()
                        )
                    }
            )
        }
        viewModelScope.launch {
            toolAuditRepository.getRecent().collect { audits ->
                _uiState.update { it.copy(audits = audits) }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { toolAuditRepository.clear() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class AttachmentMemoryUiState(
    val attachments: List<AttachmentMemory> = emptyList(),
    val query: String = "",
    val isWorking: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AttachmentMemoryViewModel @Inject constructor(
    private val attachmentMemoryRepository: AttachmentMemoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AttachmentMemoryUiState())
    val uiState: StateFlow<AttachmentMemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            attachmentMemoryRepository.getAll().collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        viewModelScope.launch {
            if (query.isNotBlank()) {
                val results = attachmentMemoryRepository.semanticSearch(query)
                _uiState.update { it.copy(attachments = results) }
            }
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            runCatching { attachmentMemoryRepository.addFromUri(uri) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { attachmentMemoryRepository.delete(id) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ReplyAssistantUiState(
    val drafts: List<ReplyDraft> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ReplyAssistantViewModel @Inject constructor(
    private val replyDraftRepository: ReplyDraftRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReplyAssistantUiState())
    val uiState: StateFlow<ReplyAssistantUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            replyDraftRepository.getAll().collect { drafts ->
                _uiState.update { it.copy(drafts = drafts) }
            }
        }
    }

    fun saveManualDraft(sender: String, originalText: String, draftText: String) {
        viewModelScope.launch {
            runCatching {
                replyDraftRepository.save(
                    ReplyDraft(
                        sourcePackage = "manual",
                        sender = sender.ifBlank { "Unknown" },
                        originalText = originalText,
                        draftText = draftText,
                        channel = "manual"
                    )
                )
            }.onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun updateDraft(draft: ReplyDraft, text: String) {
        viewModelScope.launch {
            runCatching { replyDraftRepository.update(draft.copy(draftText = text)) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun setHandled(id: Long, handled: Boolean = true) {
        viewModelScope.launch { replyDraftRepository.setHandled(id, handled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { replyDraftRepository.delete(id) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class PerformanceUiState(
    val modelLoadState: String = "Not loaded",
    val backend: String = "none",
    val modelSizeMb: Long = 0L,
    val availableStorageGb: Float = 0f,
    val totalRamGb: Float = 0f,
    val availableRamGb: Float = 0f,
    val appHeapUsedMb: Float = 0f,
    val performance: PerformanceSnapshot = PerformanceSnapshot()
)

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val gemmaInferenceEngine: GemmaInferenceEngine,
    performanceMonitor: PerformanceMonitor
) : ViewModel() {
    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            gemmaInferenceEngine.modelLoadState.collect { refresh() }
        }
        viewModelScope.launch {
            performanceMonitor.snapshot.collect { snapshot ->
                _uiState.update { it.copy(performance = snapshot) }
            }
        }
    }

    fun refresh() {
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toFloat() / (1024f * 1024f)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val state = gemmaInferenceEngine.modelLoadState.value
        _uiState.update {
            it.copy(
                modelLoadState = when (state) {
                    is ModelLoadState.NotLoaded -> "Not loaded"
                    is ModelLoadState.Loading -> "Loading ${(state.progress * 100).toInt()}%"
                    is ModelLoadState.Loaded -> "Loaded"
                    is ModelLoadState.Error -> "Error: ${state.message}"
                },
                backend = gemmaInferenceEngine.getActiveBackend(),
                modelSizeMb = modelRepository.getModelFileSize() / (1024 * 1024),
                availableStorageGb = modelRepository.getAvailableStorage().toFloat() / (1024f * 1024f * 1024f),
                totalRamGb = memoryInfo.totalMem.toFloat() / (1024f * 1024f * 1024f),
                availableRamGb = memoryInfo.availMem.toFloat() / (1024f * 1024f * 1024f),
                appHeapUsedMb = heapUsedMb
            )
        }
    }
}

data class BackupUiState(
    val backupText: String = "",
    val inputText: String = "",
    val passphrase: String = "",
    val status: String? = null,
    val isWorking: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun setPassphrase(value: String) {
        _uiState.update { it.copy(passphrase = value) }
    }

    fun setInputText(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun exportBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, status = null) }
            runCatching { backupRepository.exportEncrypted(_uiState.value.passphrase) }
                .onSuccess { backup ->
                    _uiState.update {
                        it.copy(
                            backupText = backup,
                            inputText = backup,
                            status = "Encrypted backup ready."
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    fun importBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, status = null) }
            runCatching { backupRepository.importEncrypted(_uiState.value.inputText, _uiState.value.passphrase) }
                .onSuccess { count -> _uiState.update { it.copy(status = "Imported $count conversations.") } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
            _uiState.update { it.copy(isWorking = false) }
        }
    }
}

data class ProactiveUiState(
    val proactiveAssistant: Boolean = false,
    val taskFollowups: Boolean = false,
    val dailySummary: Boolean = false,
    val pendingTasks: List<Task> = emptyList()
)

@HiltViewModel
class ProactiveViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val taskRepository: TaskRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProactiveUiState())
    val uiState: StateFlow<ProactiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.proactiveAssistant.collect { value ->
                _uiState.update { it.copy(proactiveAssistant = value) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.taskFollowups.collect { value ->
                _uiState.update { it.copy(taskFollowups = value) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.dailySummary.collect { value ->
                _uiState.update { it.copy(dailySummary = value) }
            }
        }
        viewModelScope.launch {
            taskRepository.getPendingTasks().collect { tasks ->
                _uiState.update { it.copy(pendingTasks = tasks) }
            }
        }
    }

    fun setProactiveAssistant(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setProactiveAssistant(value) }
    }

    fun setTaskFollowups(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setTaskFollowups(value) }
    }

    fun setDailySummary(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setDailySummary(value) }
    }
}
