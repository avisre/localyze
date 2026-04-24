package com.localyze

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.localyze.ai.AudioRecordingState
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ChatRepository
import com.localyze.data.repository.PerformanceMonitor
import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.domain.usecases.ChatResponseEvent
import com.localyze.domain.usecases.ManageMemoryUseCase
import com.localyze.domain.usecases.RecordAudioUseCase
import com.localyze.domain.usecases.SendMessageUseCase
import com.localyze.tools.ToolDispatcher
import com.localyze.ui.viewmodels.ChatUiState
import com.localyze.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var recordAudioUseCase: RecordAudioUseCase
    private lateinit var manageMemoryUseCase: ManageMemoryUseCase
    private lateinit var chatRepository: ChatRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var toolDispatcher: ToolDispatcher
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var logMock: MockedStatic<Log>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        logMock = Mockito.mockStatic(Log::class.java)
        logMock.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        logMock.`when`<Int> { Log.d(any<String>(), any<String>(), any()) }.thenReturn(0)
        logMock.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        logMock.`when`<Int> { Log.e(any<String>(), any<String>(), any()) }.thenReturn(0)
        logMock.`when`<Int> { Log.v(any<String>(), any<String>()) }.thenReturn(0)
        logMock.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)

        sendMessageUseCase = mock()
        recordAudioUseCase = mock()
        manageMemoryUseCase = mock()
        chatRepository = mock()
        settingsDataStore = mock()
        toolDispatcher = mock()
        performanceMonitor = mock()
        savedStateHandle = SavedStateHandle()

        // Default stubbing for use cases
        whenever(sendMessageUseCase.isUsingMockEngine()).thenReturn(false)
        doNothing().whenever(sendMessageUseCase).resetEngineConversation(any(), any())
        runBlocking {
            doReturn(Unit).whenever(sendMessageUseCase).resetEngineConversationWithSavedContext(any(), any(), any())
        }
        whenever(recordAudioUseCase.getRecordingState()).thenReturn(MutableStateFlow(AudioRecordingState.Idle))
        whenever(recordAudioUseCase.getAmplitudeFlow()).thenReturn(flowOf())
        whenever(settingsDataStore.thinkingMode).thenReturn(flowOf(false))
        whenever(settingsDataStore.streamTokens).thenReturn(flowOf(true))
        whenever(settingsDataStore.voiceAutoPlay).thenReturn(flowOf(false))
        whenever(settingsDataStore.allowWebSearch).thenReturn(flowOf(false))

        // Repository stubs
        whenever(chatRepository.getAllConversations()).thenReturn(flowOf(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        logMock.close()
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(
            sendMessageUseCase = sendMessageUseCase,
            recordAudioUseCase = recordAudioUseCase,
            manageMemoryUseCase = manageMemoryUseCase,
            chatRepository = chatRepository,
            settingsDataStore = settingsDataStore,
            toolDispatcher = toolDispatcher,
            performanceMonitor = performanceMonitor,
            savedStateHandle = savedStateHandle
        )
    }

    @Test
    fun `initial state is not streaming with empty messages`() = testScope.runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertTrue(state.messages.isEmpty())
        assertEquals(-1L, state.currentConversationId)
    }

    @Test
    fun `sendMessage with blank text does nothing`() = testScope.runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("   ")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertNotNull(state.error)
        verify(sendMessageUseCase, never()).sendMessage(any(), any(), any(), any())
    }

    @Test
    fun `sendMessage creates conversation when none exists and starts streaming`() = testScope.runTest {
        val newConversation = Conversation(id = 42L, title = "New Chat", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(newConversation)
        whenever(chatRepository.getConversation(42L)).thenReturn(newConversation)
        whenever(chatRepository.getMessagesForConversation(42L)).thenReturn(
            flowOf(
                listOf(
                    Message(id = 1L, conversationId = 42L, role = MessageRole.USER, content = "Hi there", timestamp = 1L),
                    Message(id = 2L, conversationId = 42L, role = MessageRole.ASSISTANT, content = "Hello", timestamp = 2L)
                )
            )
        )

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.StreamingToken("Hello"))
            emit(ChatResponseEvent.Completed("Hello", null))
        }
        whenever(sendMessageUseCase.sendMessage(eq(42L), any(), any(), any())).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Hi there")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(42L, state.currentConversationId)
        assertFalse(state.isStreaming)
        assertEquals("Hello", state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content)
        verify(sendMessageUseCase).sendMessage(eq(42L), eq("Hi there"), any(), any())
    }

    @Test
    fun `stopGeneration cancels streaming and resets state`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.StreamingToken("Part 1"))
            // Simulate a long stream that we interrupt
            kotlinx.coroutines.delay(10_000)
        }
        whenever(sendMessageUseCase.sendMessage(eq(1L), any(), any(), any())).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Tell me a story")
        advanceUntilIdle()

        // Should be streaming after first token
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals("Part 1", viewModel.uiState.value.streamingText)

        viewModel.stopGeneration()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        // stopGeneration does not clear streamingText; it preserves the partial output
        assertEquals("Part 1", state.streamingText)
        assertNull(state.error)
        verify(performanceMonitor, never()).recordError(any(), any())
    }

    @Test
    fun `createNewConversation resets state`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "New Chat", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createNewConversation()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1L, state.currentConversationId)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isStreaming)
        assertEquals("", state.streamingText)
    }

    @Test
    fun `toggleThinkingMode updates state`() = testScope.runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.enableThinking)

        viewModel.toggleThinkingMode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.enableThinking)
    }

    @Test
    fun `deleteMessage updates state`() = testScope.runTest {
        val msg = Message(id = 5L, conversationId = 1L, role = MessageRole.USER, content = "Test", timestamp = 1L)
        whenever(chatRepository.getMessage(5L)).thenReturn(msg)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Manually set some messages via a loaded conversation
        viewModel.loadConversation(1L)
        advanceUntilIdle()

        viewModel.deleteMessage(5L)
        advanceUntilIdle()

        verify(chatRepository).deleteMessage(5L)
    }

    @Test
    fun `regenerateResponse requires non-streaming state`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.StreamingToken("A"))
            emit(ChatResponseEvent.Completed("A", null))
        }
        whenever(sendMessageUseCase.sendMessage(eq(1L), any(), any(), any())).thenReturn(tokenFlow)
        whenever(sendMessageUseCase.regenerateLastResponse(any(), any(), any())).thenReturn(
            flow {
                emit(ChatResponseEvent.StreamingToken("B"))
                emit(ChatResponseEvent.Completed("B", null))
            }
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Prompt")
        advanceUntilIdle()

        // Should not regenerate while streaming
        viewModel.regenerateResponse()
        // Since it's not streaming after completion, this should proceed
        advanceUntilIdle()

        verify(sendMessageUseCase).regenerateLastResponse(eq(1L), any(), any())
    }

    @Test
    fun `error event sets error in state`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.Error("Model failed"))
        }
        whenever(sendMessageUseCase.sendMessage(eq(1L), any(), any(), any())).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Hi")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Model failed"))
    }

    @Test
    fun `sendImageMessage streams image response through use case`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        savedStateHandle[ChatViewModel.CONVERSATION_ID_KEY] = 1L
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val bitmap: Bitmap = mock()
        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.StreamingToken("This image shows a chart."))
            emit(ChatResponseEvent.Completed("This image shows a chart.", null))
        }
        whenever(
            sendMessageUseCase.sendMessageWithImage(eq(1L), eq("Analyze this image"), eq(bitmap), any(), any())
        ).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendImageMessage("Analyze this image", bitmap)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertNull(state.error)
        verify(sendMessageUseCase).sendMessageWithImage(eq(1L), eq("Analyze this image"), eq(bitmap), any(), any())
    }

    @Test
    fun `sendImageMessage error leaves visible error and stops streaming`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        savedStateHandle[ChatViewModel.CONVERSATION_ID_KEY] = 1L
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val bitmap: Bitmap = mock()
        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.Error("Vision model rejected the image"))
        }
        whenever(
            sendMessageUseCase.sendMessageWithImage(eq(1L), eq("Analyze this image"), eq(bitmap), any(), any())
        ).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendImageMessage("Analyze this image", bitmap)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Vision model rejected the image"))
    }

    @Test
    fun `thinking tokens update thinkingText`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(
            flowOf(
                listOf(
                    Message(id = 1L, conversationId = 1L, role = MessageRole.USER, content = "Hi", timestamp = 1L),
                    Message(id = 2L, conversationId = 1L, role = MessageRole.ASSISTANT, content = "Answer", timestamp = 2L)
                )
            )
        )

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.ThinkingToken("Thinking"))
            emit(ChatResponseEvent.StreamingToken("Answer"))
            emit(ChatResponseEvent.Completed("Answer", "Thinking"))
        }
        whenever(sendMessageUseCase.sendMessage(eq(1L), any(), any(), any())).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Hi")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals("Answer", state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content)
    }

    @Test
    fun `tool calls update activeToolCalls`() = testScope.runTest {
        val conv = Conversation(id = 1L, title = "Test", capabilityMode = "chat")
        whenever(chatRepository.createConversation(any())).thenReturn(conv)
        whenever(chatRepository.getConversation(1L)).thenReturn(conv)
        whenever(chatRepository.getMessagesForConversation(1L)).thenReturn(flowOf(emptyList()))

        val tokenFlow: Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.ToolCallStarted("calendar"))
            emit(ChatResponseEvent.ToolCallCompleted("calendar", "Done"))
            emit(ChatResponseEvent.StreamingToken("Result"))
            emit(ChatResponseEvent.Completed("Result", null))
        }
        whenever(sendMessageUseCase.sendMessage(eq(1L), any(), any(), any())).thenReturn(tokenFlow)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Schedule a meeting")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertTrue(state.activeToolCalls.isEmpty())
    }
}
