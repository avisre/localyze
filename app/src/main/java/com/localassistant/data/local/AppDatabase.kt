package com.localassistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.localassistant.domain.models.AttachmentMemory
import com.localassistant.domain.models.Conversation
import com.localassistant.domain.models.FloatListConverter
import com.localassistant.domain.models.Memory
import com.localassistant.domain.models.Message
import com.localassistant.domain.models.MessageRoleConverter
import com.localassistant.domain.models.ReplyDraft
import com.localassistant.domain.models.StringListConverter
import com.localassistant.domain.models.Task
import com.localassistant.domain.models.ToolAudit

@Database(
    entities = [
        Message::class,
        Conversation::class,
        Memory::class,
        Task::class,
        AttachmentMemory::class,
        ToolAudit::class,
        ReplyDraft::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(MessageRoleConverter::class, StringListConverter::class, FloatListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun attachmentMemoryDao(): AttachmentMemoryDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun replyDraftDao(): ReplyDraftDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_assistant_db"
                )
                    .addCallback(DatabaseCallback(context))
                    .addMigrations(*DatabaseMigrations.getAllMigrations())
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(
        private val context: Context
    ) : RoomDatabase.Callback() {
        override fun onCreate(connection: SupportSQLiteDatabase) {
            super.onCreate(connection)
            // Create FTS virtual tables
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(
                    content, thinkingContent, toolResult,
                    content=messages, tokenize=unicode61
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS conversations_fts USING fts4(
                    title,
                    content=conversations, tokenize=unicode61
                )
                """.trimIndent()
            )
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // Create FTS tables if they don't exist (for existing databases)
            try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(" +
                        "content, thinkingContent, toolResult, content=messages, tokenize=unicode61)"
                )
            } catch (_: Exception) {
            }
            try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS conversations_fts USING fts4(" +
                        "title, content=conversations, tokenize=unicode61)"
                )
            } catch (_: Exception) {
            }
        }
    }
}
