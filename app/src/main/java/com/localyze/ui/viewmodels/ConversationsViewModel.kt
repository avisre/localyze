package com.localyze.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localyze.data.repository.ChatRepository
import com.localyze.domain.models.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConversationFilter {
    ACTIVE,
    FAVORITES,
    ARCHIVED,
    ALL
}

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val folders: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val filter: ConversationFilter = ConversationFilter.ACTIVE,
    val folderFilter: String = "",
    val selectedIds: Set<Long> = emptySet(),
    val showDeleteConfirmDialog: Boolean = false,
    val conversationToDelete: Conversation? = null,
    val showClearAllConfirmDialog: Boolean = false,
    val messageSearchConversationIds: Set<Long> = emptySet()
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()
    private var messageSearchJob: Job? = null

    init {
        loadConversations()
        loadFolders()
    }

    fun loadConversations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                chatRepository.getAllConversations().collect { conversations ->
                    _uiState.update { it.copy(conversations = conversations, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            chatRepository.getFolders().collect { folders ->
                _uiState.update { it.copy(folders = folders) }
            }
        }
    }

    fun createConversation(capabilityMode: String = "chat", title: String = "New Chat"): Long {
        var newId = -1L
        viewModelScope.launch {
            try {
                val conversation = chatRepository.createConversation(capabilityMode)
                if (title != "New Chat") {
                    chatRepository.updateConversation(conversation.copy(title = title))
                }
                newId = conversation.id
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
        return newId
    }

    fun updateConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                chatRepository.updateConversation(conversation)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            try {
                chatRepository.deleteConversation(conversationId)
                _uiState.update {
                    it.copy(
                        showDeleteConfirmDialog = false,
                        conversationToDelete = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun togglePinConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                val updated = conversation.copy(isPinned = !conversation.isPinned)
                chatRepository.updateConversation(updated)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleFavoriteConversation(conversation: Conversation) {
        viewModelScope.launch {
            runCatching {
                chatRepository.favoriteConversation(conversation.id, !conversation.isFavorite)
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleArchiveConversation(conversation: Conversation) {
        viewModelScope.launch {
            runCatching {
                chatRepository.archiveConversation(conversation.id, !conversation.isArchived)
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun updateConversationFolder(conversation: Conversation, folder: String) {
        viewModelScope.launch {
            runCatching {
                chatRepository.updateConversationFolder(conversation.id, folder)
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun updateFilter(filter: ConversationFilter) {
        _uiState.update { it.copy(filter = filter, selectedIds = emptySet()) }
    }

    fun updateFolderFilter(folder: String) {
        _uiState.update { it.copy(folderFilter = folder, selectedIds = emptySet()) }
    }

    fun toggleSelection(conversationId: Long) {
        _uiState.update { state ->
            val next = if (conversationId in state.selectedIds) {
                state.selectedIds - conversationId
            } else {
                state.selectedIds + conversationId
            }
            state.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun bulkArchiveSelected(archived: Boolean) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds
            runCatching {
                chatRepository.archiveConversations(ids, archived)
            }.onSuccess {
                _uiState.update { it.copy(selectedIds = emptySet()) }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun bulkDeleteSelected() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds
            runCatching {
                chatRepository.deleteConversations(ids)
            }.onSuccess {
                _uiState.update { it.copy(selectedIds = emptySet()) }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            try {
                _uiState.value.conversations.forEach { conversation ->
                    chatRepository.deleteConversation(conversation.id)
                }
                _uiState.update { it.copy(showClearAllConfirmDialog = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun showDeleteConfirmDialog(conversation: Conversation) {
        _uiState.update {
            it.copy(
                showDeleteConfirmDialog = true,
                conversationToDelete = conversation
            )
        }
    }

    fun dismissDeleteConfirmDialog() {
        _uiState.update {
            it.copy(
                showDeleteConfirmDialog = false,
                conversationToDelete = null
            )
        }
    }

    fun showClearAllConfirmDialog() {
        _uiState.update { it.copy(showClearAllConfirmDialog = true) }
    }

    fun dismissClearAllConfirmDialog() {
        _uiState.update { it.copy(showClearAllConfirmDialog = false) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        messageSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(messageSearchConversationIds = emptySet()) }
            return
        }
        messageSearchJob = viewModelScope.launch {
            try {
                chatRepository.searchMessages(query).collect { messages ->
                    _uiState.update {
                        it.copy(messageSearchConversationIds = messages.map { message -> message.conversationId }.toSet())
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun exportConversation(conversationId: Long, onExportReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val exportText = chatRepository.exportConversation(conversationId)
                if (exportText == null) {
                    _uiState.update { it.copy(error = "Conversation could not be exported") }
                } else {
                    onExportReady(exportText)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun exportSelected(onExportReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val exportText = chatRepository.exportConversations(_uiState.value.selectedIds)
                onExportReady(exportText)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun getFilteredConversations(): List<Conversation> {
        val state = _uiState.value
        var filtered = state.conversations

        filtered = when (state.filter) {
            ConversationFilter.ACTIVE -> filtered.filter { !it.isArchived }
            ConversationFilter.FAVORITES -> filtered.filter { it.isFavorite && !it.isArchived }
            ConversationFilter.ARCHIVED -> filtered.filter { it.isArchived }
            ConversationFilter.ALL -> filtered
        }

        if (state.folderFilter.isNotBlank()) {
            filtered = filtered.filter { it.folder == state.folderFilter }
        }

        // Apply search
        if (state.searchQuery.isNotBlank()) {
            val query = state.searchQuery.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(query) ||
                    it.folder.lowercase().contains(query) ||
                    it.tags.any { tag -> tag.lowercase().contains(query) } ||
                    it.summary?.lowercase()?.contains(query) == true ||
                    it.id in state.messageSearchConversationIds
            }
        }

        return filtered
    }

    fun getPinnedConversations(): List<Conversation> {
        return getFilteredConversations().filter { it.isPinned }
    }

    fun getUnpinnedConversations(): List<Conversation> {
        return getFilteredConversations().filter { !it.isPinned }
    }
}
