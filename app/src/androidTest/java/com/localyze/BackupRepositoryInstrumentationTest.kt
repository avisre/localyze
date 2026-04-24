package com.localyze

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localyze.data.local.AppDatabase
import com.localyze.data.repository.BackupRepository
import com.localyze.domain.models.AttachmentMemory
import com.localyze.domain.models.Conversation
import com.localyze.domain.models.Memory
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.domain.models.ReplyDraft
import com.localyze.domain.models.Task
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRepositoryInstrumentationTest {

    private lateinit var sourceDb: AppDatabase
    private lateinit var restoreDb: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        restoreDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        restoreDb.close()
    }

    @Test
    fun encryptedBackupRoundTrip_preservesCoreUserData() = runBlocking {
        val sourceConversationId = sourceDb.conversationDao().insert(
            Conversation(
                title = "Backup smoke",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                isPinned = true,
                tags = listOf("qa", "backup"),
                summary = "Round-trip test conversation",
                messageCount = 2
            )
        )
        val sourceUserMessageId = sourceDb.messageDao().insert(
            Message(
                conversationId = sourceConversationId,
                role = MessageRole.USER,
                content = "Please remember this attachment.",
                timestamp = 2_100L
            )
        )
        sourceDb.messageDao().insert(
            Message(
                conversationId = sourceConversationId,
                role = MessageRole.ASSISTANT,
                content = "Saved locally.",
                timestamp = 2_200L
            )
        )
        sourceDb.memoryDao().insert(
            Memory(
                content = "User prefers offline AI.",
                keywords = listOf("preference", "offline"),
                createdAt = 3_000L,
                lastAccessedAt = 3_100L
            )
        )
        sourceDb.taskDao().insert(
            Task(
                title = "Ship QA build",
                description = "Run regression checks",
                dueDate = 4_000L
            )
        )
        sourceDb.attachmentMemoryDao().insert(
            AttachmentMemory(
                conversationId = sourceConversationId,
                messageId = sourceUserMessageId,
                uri = "content://localyze/test.txt",
                displayName = "test.txt",
                mimeType = "text/plain",
                extractedText = "Important attachment text",
                summary = "Saved text file",
                embedding = listOf(0.1f, 0.2f, 0.3f)
            )
        )
        sourceDb.replyDraftDao().insert(
            ReplyDraft(
                sourcePackage = "com.example.mail",
                sender = "qa@example.com",
                originalText = "Can we ship?",
                draftText = "Yes, after the regression pass."
            )
        )

        val backup = backupRepository(sourceDb).exportEncrypted("correct-passphrase")
        assertTrue("Backup should use the current Localyze envelope", backup.startsWith("LA_BACKUP_V1:"))

        val importedConversations = backupRepository(restoreDb).importEncrypted(backup, "correct-passphrase")

        assertEquals("Should import one conversation", 1, importedConversations)
        val restoredConversation = restoreDb.conversationDao().getAllConversationsList().single()
        assertEquals("Conversation title should round-trip", "Backup smoke", restoredConversation.title)
        assertEquals("Conversation tags should round-trip", listOf("qa", "backup"), restoredConversation.tags)

        val restoredMessages = restoreDb.messageDao().getAllMessagesList()
        assertEquals("Messages should round-trip", 2, restoredMessages.size)
        assertTrue(restoredMessages.all { it.conversationId == restoredConversation.id })
        assertTrue(restoredMessages.any { it.role == MessageRole.USER && it.content.contains("attachment") })
        assertTrue(restoredMessages.any { it.role == MessageRole.ASSISTANT && it.content == "Saved locally." })

        val restoredMemory = restoreDb.memoryDao().getAllMemoriesList().single()
        assertEquals("Memory content should round-trip", "User prefers offline AI.", restoredMemory.content)
        assertEquals("Memory keywords should round-trip", listOf("preference", "offline"), restoredMemory.keywords)

        val restoredTask = restoreDb.taskDao().getAllTasksList().single()
        assertEquals("Task title should round-trip", "Ship QA build", restoredTask.title)

        val restoredAttachment = restoreDb.attachmentMemoryDao().getAllList().single()
        assertEquals("Attachment conversation should be remapped", restoredConversation.id, restoredAttachment.conversationId)
        assertNotNull("Attachment message should be remapped", restoredAttachment.messageId)
        assertTrue(restoredMessages.any { it.id == restoredAttachment.messageId })
        assertEquals("Attachment text should round-trip", "Important attachment text", restoredAttachment.extractedText)

        val restoredDraft = restoreDb.replyDraftDao().getAllList().single()
        assertEquals("Reply draft sender should round-trip", "qa@example.com", restoredDraft.sender)
        assertEquals("Reply draft text should round-trip", "Yes, after the regression pass.", restoredDraft.draftText)
    }

    private fun backupRepository(db: AppDatabase): BackupRepository =
        BackupRepository(
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            memoryDao = db.memoryDao(),
            taskDao = db.taskDao(),
            attachmentMemoryDao = db.attachmentMemoryDao(),
            replyDraftDao = db.replyDraftDao()
        )
}
