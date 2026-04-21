package com.localassistant.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Capabilities screen (Tab 2 of bottom navigation).
 *
 * Manages the selection of a capability mode and the creation of a new
 * conversation with that mode. The resulting conversation ID is exposed
 * so the navigation layer can route the user to the chat screen.
 */
@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _selectedMode = MutableStateFlow<String?>(null)
    val selectedMode: StateFlow<String?> = _selectedMode.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    /** The ID of the newly created conversation, set after [selectCapability] succeeds. */
    private val _createdConversationId = MutableStateFlow<Long?>(null)
    val createdConversationId: StateFlow<Long?> = _createdConversationId.asStateFlow()

    /**
     * Create a new conversation with the selected capability mode and
     * return the conversation ID via [createdConversationId].
     */
    fun selectCapability(mode: String) {
        // Prevent duplicate creation while one is in progress
        if (_isCreating.value) return

        _selectedMode.value = mode
        _isCreating.value = true

        viewModelScope.launch {
            try {
                val conversation = chatRepository.createConversation(capabilityMode = mode)
                _createdConversationId.value = conversation.id
            } catch (_: Exception) {
                // Reset state so the user can retry
                _selectedMode.value = null
            } finally {
                _isCreating.value = false
            }
        }
    }

    /**
     * Reset the created conversation ID after it has been consumed by
     * the navigation layer, preventing duplicate navigations.
     */
    fun consumeConversationId() {
        _createdConversationId.value = null
    }
}