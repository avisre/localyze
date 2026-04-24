package com.localyze

import com.localyze.data.repository.ChatRepository
import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.ui.viewmodels.ConversationFilter
import com.localyze.ui.viewmodels.ConversationsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var chatRepository: ChatRepository
    private lateinit var viewModel: ConversationsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        chatRepository = mock()
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(emptyList()))
        whenever(chatRepository.getFolders()).thenReturn(flowOf(emptyList()))

        viewModel = ConversationsViewModel(chatRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty conversations and no selection`() = testScope.runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.conversations.isEmpty())
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(ConversationFilter.ACTIVE, state.filter)
    }

    @Test
    fun `loadConversations updates state with repository data`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Chat 1", capabilityMode = "chat"),
            Conversation(id = 2L, title = "Chat 2", capabilityMode = "code")
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))

        viewModel.loadConversations()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.conversations.size)
        assertEquals("Chat 1", viewModel.uiState.value.conversations[0].title)
    }

    @Test
    fun `createConversation returns valid id`() = testScope.runTest {
        val newConversation = Conversation(id = 42L, title = "New Chat", capabilityMode = "chat")
        whenever(chatRepository.createConversation("chat")).thenReturn(newConversation)

        val id = viewModel.createConversation(capabilityMode = "chat")
        advanceUntilIdle()

        assertEquals(42L, id)
        verify(chatRepository).createConversation("chat")
    }

    @Test
    fun `createConversation with custom title updates title`() = testScope.runTest {
        val newConversation = Conversation(id = 3L, title = "New Chat", capabilityMode = "chat")
        whenever(chatRepository.createConversation("chat")).thenReturn(newConversation)

        viewModel.createConversation(capabilityMode = "chat", title = "Custom Title")
        advanceUntilIdle()

        verify(chatRepository).updateConversation(newConversation.copy(title = "Custom Title"))
    }

    @Test
    fun `createConversation propagates error to state`() = testScope.runTest {
        whenever(chatRepository.createConversation(any())).thenThrow(RuntimeException("DB error"))

        val id = viewModel.createConversation()
        advanceUntilIdle()

        assertEquals(-1L, id)
        assertEquals("DB error", viewModel.uiState.value.error)
    }

    @Test
    fun `updateConversation delegates to repository`() = testScope.runTest {
        val conversation = Conversation(id = 1L, title = "Updated", capabilityMode = "chat")
        viewModel.updateConversation(conversation)
        advanceUntilIdle()

        verify(chatRepository).updateConversation(conversation)
    }

    @Test
    fun `deleteConversation clears dialog state`() = testScope.runTest {
        viewModel.showDeleteConfirmDialog(Conversation(id = 1L, title = "To Delete", capabilityMode = "chat"))
        assertTrue(viewModel.uiState.value.showDeleteConfirmDialog)

        viewModel.deleteConversation(1L)
        advanceUntilIdle()

        verify(chatRepository).deleteConversation(1L)
        assertFalse(viewModel.uiState.value.showDeleteConfirmDialog)
        assertNull(viewModel.uiState.value.conversationToDelete)
    }

    @Test
    fun `togglePinConversation delegates to repository`() = testScope.runTest {
        val conversation = Conversation(id = 1L, title = "Chat", isPinned = false)
        viewModel.togglePinConversation(conversation)
        advanceUntilIdle()

        verify(chatRepository).updateConversation(conversation.copy(isPinned = true))
    }

    @Test
    fun `toggleFavoriteConversation delegates to repository`() = testScope.runTest {
        val conversation = Conversation(id = 1L, title = "Chat", isFavorite = false)
        viewModel.toggleFavoriteConversation(conversation)
        advanceUntilIdle()

        verify(chatRepository).favoriteConversation(1L, true)
    }

    @Test
    fun `toggleArchiveConversation delegates to repository`() = testScope.runTest {
        val conversation = Conversation(id = 1L, title = "Chat", isArchived = false)
        viewModel.toggleArchiveConversation(conversation)
        advanceUntilIdle()

        verify(chatRepository).archiveConversation(1L, true)
    }

    @Test
    fun `updateFilter changes filter and clears selection`() = testScope.runTest {
        viewModel.toggleSelection(1L)
        assertTrue(viewModel.uiState.value.selectedIds.contains(1L))

        viewModel.updateFilter(ConversationFilter.ARCHIVED)

        assertEquals(ConversationFilter.ARCHIVED, viewModel.uiState.value.filter)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `toggleSelection adds and removes ids`() {
        viewModel.toggleSelection(1L)
        assertTrue(viewModel.uiState.value.selectedIds.contains(1L))

        viewModel.toggleSelection(2L)
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.selectedIds)

        viewModel.toggleSelection(1L)
        assertEquals(setOf(2L), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `clearSelection empties selected ids`() {
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.clearSelection()
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `bulkArchiveSelected delegates to repository and clears selection`() = testScope.runTest {
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)

        viewModel.bulkArchiveSelected(true)
        advanceUntilIdle()

        verify(chatRepository).archiveConversations(setOf(1L, 2L), true)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `bulkDeleteSelected delegates to repository and clears selection`() = testScope.runTest {
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(3L)

        viewModel.bulkDeleteSelected()
        advanceUntilIdle()

        verify(chatRepository).deleteConversations(setOf(1L, 3L))
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `clearAllConversations deletes all and dismisses dialog`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "A", capabilityMode = "chat"),
            Conversation(id = 2L, title = "B", capabilityMode = "chat")
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel.loadConversations()
        advanceUntilIdle()

        viewModel.showClearAllConfirmDialog()
        assertTrue(viewModel.uiState.value.showClearAllConfirmDialog)

        viewModel.clearAllConversations()
        advanceUntilIdle()

        verify(chatRepository).deleteConversation(1L)
        verify(chatRepository).deleteConversation(2L)
        assertFalse(viewModel.uiState.value.showClearAllConfirmDialog)
    }

    @Test
    fun `getFilteredConversations applies active filter`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Active", isArchived = false),
            Conversation(id = 2L, title = "Archived", isArchived = true)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()

        val filtered = viewModel.getFilteredConversations()
        assertEquals(1, filtered.size)
        assertEquals("Active", filtered[0].title)
    }

    @Test
    fun `getFilteredConversations applies favorites filter`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Fav", isFavorite = true, isArchived = false),
            Conversation(id = 2L, title = "Normal", isFavorite = false, isArchived = false),
            Conversation(id = 3L, title = "Archived Fav", isFavorite = true, isArchived = true)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()
        viewModel.updateFilter(ConversationFilter.FAVORITES)

        val filtered = viewModel.getFilteredConversations()
        assertEquals(1, filtered.size)
        assertEquals("Fav", filtered[0].title)
    }

    @Test
    fun `getFilteredConversations applies archived filter`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Active", isArchived = false),
            Conversation(id = 2L, title = "Archived", isArchived = true)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()
        viewModel.updateFilter(ConversationFilter.ARCHIVED)

        val filtered = viewModel.getFilteredConversations()
        assertEquals(1, filtered.size)
        assertEquals("Archived", filtered[0].title)
    }

    @Test
    fun `getFilteredConversations applies search query`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Kotlin Chat", isArchived = false),
            Conversation(id = 2L, title = "Java Chat", isArchived = false)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()
        viewModel.updateSearchQuery("kotlin")

        val filtered = viewModel.getFilteredConversations()
        assertEquals(1, filtered.size)
        assertEquals("Kotlin Chat", filtered[0].title)
    }

    @Test
    fun `getPinnedConversations returns only pinned`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Pinned", isPinned = true),
            Conversation(id = 2L, title = "Unpinned", isPinned = false)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()

        val pinned = viewModel.getPinnedConversations()
        assertEquals(1, pinned.size)
        assertEquals("Pinned", pinned[0].title)
    }

    @Test
    fun `getUnpinnedConversations returns only unpinned`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Pinned", isPinned = true),
            Conversation(id = 2L, title = "Unpinned", isPinned = false)
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()

        val unpinned = viewModel.getUnpinnedConversations()
        assertEquals(1, unpinned.size)
        assertEquals("Unpinned", unpinned[0].title)
    }

    @Test
    fun `updateSearchQuery with blank query clears message search ids`() = testScope.runTest {
        viewModel.updateSearchQuery("test")
        viewModel.updateSearchQuery("")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messageSearchConversationIds.isEmpty())
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `exportConversation invokes callback with export text`() = testScope.runTest {
        whenever(chatRepository.exportConversation(1L)).thenReturn("# Export")

        var exported: String? = null
        viewModel.exportConversation(1L) { exported = it }
        advanceUntilIdle()

        assertEquals("# Export", exported)
    }

    @Test
    fun `exportConversation sets error when export is null`() = testScope.runTest {
        whenever(chatRepository.exportConversation(1L)).thenReturn(null)

        viewModel.exportConversation(1L) {}
        advanceUntilIdle()

        assertEquals("Conversation could not be exported", viewModel.uiState.value.error)
    }

    @Test
    fun `exportSelected invokes callback with export text`() = testScope.runTest {
        whenever(chatRepository.exportConversations(setOf(1L, 2L))).thenReturn("# Multi Export")
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)

        var exported: String? = null
        viewModel.exportSelected { exported = it }
        advanceUntilIdle()

        assertEquals("# Multi Export", exported)
    }

    @Test
    fun `clearError resets error state`() {
        viewModel.showDeleteConfirmDialog(Conversation(id = 1L, title = "X"))
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `showDeleteConfirmDialog sets dialog state`() {
        val conversation = Conversation(id = 1L, title = "To Delete")
        viewModel.showDeleteConfirmDialog(conversation)
        assertTrue(viewModel.uiState.value.showDeleteConfirmDialog)
        assertEquals(conversation, viewModel.uiState.value.conversationToDelete)
    }

    @Test
    fun `dismissDeleteConfirmDialog clears dialog state`() {
        viewModel.showDeleteConfirmDialog(Conversation(id = 1L, title = "X"))
        viewModel.dismissDeleteConfirmDialog()
        assertFalse(viewModel.uiState.value.showDeleteConfirmDialog)
        assertNull(viewModel.uiState.value.conversationToDelete)
    }

    @Test
    fun `showClearAllConfirmDialog sets dialog state`() {
        viewModel.showClearAllConfirmDialog()
        assertTrue(viewModel.uiState.value.showClearAllConfirmDialog)
    }

    @Test
    fun `dismissClearAllConfirmDialog clears dialog state`() {
        viewModel.showClearAllConfirmDialog()
        viewModel.dismissClearAllConfirmDialog()
        assertFalse(viewModel.uiState.value.showClearAllConfirmDialog)
    }

    @Test
    fun `updateFolderFilter changes folder and clears selection`() {
        viewModel.toggleSelection(1L)
        viewModel.updateFolderFilter("Work")
        assertEquals("Work", viewModel.uiState.value.folderFilter)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `folder filter affects getFilteredConversations`() = testScope.runTest {
        val conversations = listOf(
            Conversation(id = 1L, title = "Work A", folder = "Work"),
            Conversation(id = 2L, title = "Personal B", folder = "Personal")
        )
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(conversations))
        viewModel = ConversationsViewModel(chatRepository)
        advanceUntilIdle()
        viewModel.updateFolderFilter("Work")

        val filtered = viewModel.getFilteredConversations()
        assertEquals(1, filtered.size)
        assertEquals("Work A", filtered[0].title)
    }
}
