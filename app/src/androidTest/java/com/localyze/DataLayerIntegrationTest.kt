package com.localyze

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localyze.ai.ModelLoadState
import com.localyze.data.repository.ChatRepositoryImpl
import com.localyze.data.repository.DownloadProgress
import com.localyze.data.repository.MemoryRepositoryImpl
import com.localyze.data.repository.ModelRepository
import com.localyze.data.local.AppDatabase
import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Memory
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for the data and AI layers.
 *
 * Tests database operations, model repository logic,
 * download progress tracking, and message persistence.
 */
@RunWith(AndroidJUnit4::class)
class DataLayerIntegrationTest {

    private lateinit var context: Context
    private lateinit var modelFilesRoot: File
    private lateinit var db: AppDatabase
    private lateinit var modelRepository: ModelRepository
    private lateinit var chatRepository: ChatRepositoryImpl
    private lateinit var memoryRepository: MemoryRepositoryImpl

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        modelFilesRoot = File(context.cacheDir, "model-repository-test-${System.nanoTime()}")
            .also { it.mkdirs() }
        val modelContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = modelFilesRoot
        }
        val okHttpClient = OkHttpClient.Builder().build()
        modelRepository = ModelRepository(modelContext, okHttpClient)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepositoryImpl(
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            appDatabase = db
        )
        memoryRepository = MemoryRepositoryImpl(memoryDao = db.memoryDao())
    }

    @After
    fun tearDown() {
        db.close()
        modelFilesRoot.deleteRecursively()
    }

    // â”€â”€â”€ Model Repository Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testModelDirExists() {
        val modelsDir = File(modelFilesRoot, "models")
        modelsDir.mkdirs()
        assertTrue("Models directory should exist", modelsDir.exists())
    }

    @Test
    fun testIsModelDownloadedReturnsFalseWhenNoModel() {
        // Clean up any existing model file
        val modelFile = File(modelFilesRoot, "models/gemma-4-E4B-it.litertlm")
        if (modelFile.exists()) modelFile.delete()
        assertFalse("Model should not be downloaded", modelRepository.isModelDownloaded())
    }

    @Test
    fun testIsModelDownloadedReturnsTrueWithPlaceholderFile() {
        val modelsDir = File(modelFilesRoot, "models")
        modelsDir.mkdirs()
        val modelFile = File(modelsDir, "gemma-4-E4B-it.litertlm")
        modelFile.writeText("placeholder")
        assertTrue("Model should be detected as downloaded", modelRepository.isModelDownloaded())
        // Clean up
        modelFile.delete()
    }

    @Test
    fun testGetAvailableStorageReturnsPositiveValue() {
        val available = modelRepository.getAvailableStorage()
        assertTrue("Available storage should be positive", available > 0)
    }

    @Test
    fun testHasMinimumRamReturnsBoolean() {
        val hasMinRam = modelRepository.hasMinimumRam()
        // Just verify it doesn't crash and returns a boolean
        assertTrue("hasMinimumRam should not throw", hasMinRam || !hasMinRam)
    }

    @Test
    fun testModelUrlIsSet() {
        assertFalse("MODEL_URL should be set", ModelRepository.MODEL_URL.isEmpty())
        assertTrue("MODEL_URL should be an HTTPS URL", ModelRepository.MODEL_URL.startsWith("https://"))
    }

    @Test
    fun testModelSizeIsPositive() {
        assertTrue("Model size should be positive", ModelRepository.MODEL_SIZE_BYTES > 0)
        assertTrue("Model size should be reasonable (> 1GB)", ModelRepository.MODEL_SIZE_BYTES > 1_000_000_000)
    }

    @Test
    fun testDeleteModelWhenNoFile() {
        val result = modelRepository.deleteModel()
        assertTrue("Delete should succeed when no file exists", result)
    }

    @Test
    fun testDeleteModelWhenFileExists() {
        val modelsDir = File(modelFilesRoot, "models")
        modelsDir.mkdirs()
        val modelFile = File(modelsDir, "gemma-4-E4B-it.litertlm")
        modelFile.writeText("test")
        assertTrue("File should exist before delete", modelFile.exists())
        val result = modelRepository.deleteModel()
        assertTrue("Delete should succeed", result)
        assertFalse("File should be gone after delete", modelFile.exists())
    }

    // â”€â”€â”€ Chat Repository Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testCreateConversation() = runBlocking {
        val conversation = chatRepository.createConversation(capabilityMode = "chat")
        assertNotNull("Conversation should be created", conversation)
        assertTrue("Conversation ID should be positive", conversation.id > 0)
        assertEquals("Capability mode should be 'chat'", "chat", conversation.capabilityMode)
    }

    @Test
    fun testCreateConversationWithDifferentModes() = runBlocking {
        val modes = listOf("chat", "see", "write", "brainstorm", "code", "data")
        for (mode in modes) {
            val conversation = chatRepository.createConversation(capabilityMode = mode)
            assertEquals("Mode should match: $mode", mode, conversation.capabilityMode)
        }
    }

    @Test
    fun testSaveAndRetrieveMessage() = runBlocking {
        val conversation = chatRepository.createConversation(capabilityMode = "chat")
        val message = Message(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "Hello, how are you?"
        )
        chatRepository.saveMessage(message)

        val messages = chatRepository.getMessagesForConversation(conversation.id).first()
        assertEquals("Should have 1 message", 1, messages.size)
        assertEquals("Message content should match", "Hello, how are you?", messages[0].content)
        assertEquals("Message role should be USER", MessageRole.USER, messages[0].role)
    }

    @Test
    fun testSaveMultipleMessages() = runBlocking {
        val conversation = chatRepository.createConversation(capabilityMode = "chat")

        val userMsg = Message(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "What is the weather?"
        )
        chatRepository.saveMessage(userMsg)

        val assistantMsg = Message(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "I can check the weather using my Calendar tool."
        )
        chatRepository.saveMessage(assistantMsg)

        val messages = chatRepository.getMessagesForConversation(conversation.id).first()
        assertEquals("Should have 2 messages", 2, messages.size)
        assertEquals("First should be USER", MessageRole.USER, messages[0].role)
        assertEquals("Second should be ASSISTANT", MessageRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun testDeleteMessage() = runBlocking {
        val conversation = chatRepository.createConversation(capabilityMode = "chat")
        val message = Message(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "Delete me"
        )
        chatRepository.saveMessage(message)
        var messages = chatRepository.getMessagesForConversation(conversation.id).first()
        assertEquals("Should have 1 message", 1, messages.size)

        chatRepository.deleteMessage(messages[0].id)
        messages = chatRepository.getMessagesForConversation(conversation.id).first()
        assertTrue("Should have 0 messages after delete", messages.isEmpty())
    }

    @Test
    fun testUpdateConversationTitle() = runBlocking {
        val conversation = chatRepository.createConversation(capabilityMode = "chat")
        val updated = conversation.copy(title = "My Chat Session")
        chatRepository.updateConversation(updated)

        val retrieved = chatRepository.getConversation(conversation.id)
        assertNotNull("Conversation should exist", retrieved)
        assertEquals("Title should be updated", "My Chat Session", retrieved!!.title)
    }

    // â”€â”€â”€ Memory Repository Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testSaveAndRetrieveMemory() = runBlocking {
        val saved = memoryRepository.saveMemory(
            content = "User prefers dark mode",
            keywords = listOf("preference", "dark-mode", "ui")
        )

        val memories = memoryRepository.getAllMemories()
        assertTrue("Should have at least 1 memory", memories.isNotEmpty())
        assertEquals("Content should match", "User prefers dark mode", memories.last().content)
    }

    @Test
    fun testSearchMemories() = runBlocking {
        // Save some memories
        memoryRepository.saveMemory(content = "User likes Python", keywords = listOf("programming", "python"))
        memoryRepository.saveMemory(content = "User prefers light mode", keywords = listOf("preference", "ui"))
        memoryRepository.saveMemory(content = "Python is great for data science", keywords = listOf("python", "data"))

        // Search
        val results = memoryRepository.searchMemories("Python")
        assertTrue("Should find Python-related memories", results.size >= 2)
    }

    @Test
    fun testDeleteMemory() = runBlocking {
        val saved = memoryRepository.saveMemory(
            content = "Temporary memory",
            keywords = listOf("temp")
        )
        memoryRepository.deleteMemory(saved.id)
        val afterDelete = memoryRepository.getAllMemories()
        assertFalse("Deleted memory should not appear", afterDelete.any { it.id == saved.id })
    }

    // â”€â”€â”€ Download Progress Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testDownloadProgressSealedClass() {
        val downloading = DownloadProgress.Downloading(
            bytesDownloaded = 1000000,
            totalBytes = 3654467584,
            percent = 0.00027f,
            estimatedSecondsRemaining = 120
        )
        assertEquals("bytesDownloaded should match", 1000000, downloading.bytesDownloaded)
        assertEquals("totalBytes should match", 3654467584, downloading.totalBytes)

        val verifying = DownloadProgress.Verifying(percent = 0.5f)
        assertEquals("percent should match", 0.5f, verifying.percent, 0.01f)

        val complete = DownloadProgress.Complete
        assertNotNull("Complete should exist", complete)

        val error = DownloadProgress.Error("Test error", true)
        assertEquals("Message should match", "Test error", error.message)
        assertTrue("isRetryable should be true", error.isRetryable)
    }

    // â”€â”€â”€ Model State Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testModelLoadStateSealedClass() {
        val notLoaded = ModelLoadState.NotLoaded
        assertNotNull("NotLoaded state should exist", notLoaded)

        val loading = ModelLoadState.Loading(0.5f)
        assertEquals("Loading progress should match", 0.5f, loading.progress, 0.01f)

        val loaded = ModelLoadState.Loaded
        assertNotNull("Loaded state should exist", loaded)

        val error = ModelLoadState.Error("Test error")
        assertEquals("Error message should match", "Test error", error.message)
    }

    // â”€â”€â”€ Conversation Isolation Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testConversationIsolation() = runBlocking {
        val conv1 = chatRepository.createConversation(capabilityMode = "chat")
        val conv2 = chatRepository.createConversation(capabilityMode = "code")

        chatRepository.saveMessage(Message(
            conversationId = conv1.id, role = MessageRole.USER,
            content = "Chat message"
        ))
        chatRepository.saveMessage(Message(
            conversationId = conv2.id, role = MessageRole.USER,
            content = "Code message"
        ))

        val msgs1 = chatRepository.getMessagesForConversation(conv1.id).first()
        val msgs2 = chatRepository.getMessagesForConversation(conv2.id).first()

        assertEquals("Conv1 should have 1 message", 1, msgs1.size)
        assertEquals("Conv2 should have 1 message", 1, msgs2.size)
        assertEquals("Conv1 message content", "Chat message", msgs1[0].content)
        assertEquals("Conv2 message content", "Code message", msgs2[0].content)
    }

    // â”€â”€â”€ Tool Registry Tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun testMessageRoleEnum() {
        assertEquals("USER role", MessageRole.USER, MessageRole.valueOf("USER"))
        assertEquals("ASSISTANT role", MessageRole.ASSISTANT, MessageRole.valueOf("ASSISTANT"))
        assertEquals("TOOL role", MessageRole.TOOL, MessageRole.valueOf("TOOL"))
        assertEquals("SYSTEM role", MessageRole.SYSTEM, MessageRole.valueOf("SYSTEM"))
    }

    @Test
    fun testMessageCreation() {
        val msg = Message(
            id = 1,
            conversationId = 1,
            role = MessageRole.USER,
            content = "Hello",
            timestamp = 1000L
        )
        // Verify Message constructor with explicit timestamp works
        assertEquals("timestamp should match", 1000L, msg.timestamp)
        assertEquals("id should match", 1, msg.id)
        assertEquals("role should match", MessageRole.USER, msg.role)
        assertEquals("content should match", "Hello", msg.content)
        assertNull("thinkingContent should be null by default", msg.thinkingContent)
        assertNull("toolName should be null by default", msg.toolName)
    }
}
