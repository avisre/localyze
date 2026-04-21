package com.localassistant.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for LocalAssistant.
 *
 * Migration philosophy:
 * - Always preserve user data when possible
 * - Add indexes for performance without data loss
 * - Use CASCADE for referential integrity
 */
object DatabaseMigrations {

    /**
     * Migration from version 1 to version 2:
     * - Adds foreign key constraint to messages table (conversationId -> conversations.id)
     * - Adds indexes on messages (conversationId + timestamp, timestamp, role)
     * - Adds indexes on conversations (updatedAt, isPinned + updatedAt)
     *
     * Note: SQLite doesn't support adding FK constraints to existing tables.
     * We need to create a new table, copy data, drop old, rename new.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new messages table with foreign key constraint
            db.execSQL(
                """
                CREATE TABLE messages_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversationId INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    thinkingContent TEXT,
                    toolCallId TEXT,
                    toolName TEXT,
                    toolResult TEXT,
                    imageUris TEXT NOT NULL DEFAULT '',
                    audioPath TEXT,
                    timestamp INTEGER NOT NULL,
                    isStreaming INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            // Copy data from old messages table
            db.execSQL(
                """
                INSERT INTO messages_new (
                    id, conversationId, role, content, thinkingContent,
                    toolCallId, toolName, toolResult, imageUris, audioPath, timestamp, isStreaming
                ) SELECT
                    id, conversationId, role, content, thinkingContent,
                    toolCallId, toolName, toolResult, COALESCE(imageUris, ''), audioPath, timestamp, isStreaming
                FROM messages
                """.trimIndent()
            )

            // Drop old messages table
            db.execSQL("DROP TABLE messages")

            // Rename new messages table
            db.execSQL("ALTER TABLE messages_new RENAME TO messages")

            // Create indexes on messages table
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages(conversationId, timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages(role)")

            // Create indexes on conversations table
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_updatedAt ON conversations(updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_isPinned_updatedAt ON conversations(isPinned, updatedAt)")
        }
    }

    /**
     * Migration from version 2 to version 3:
     * - Adds archive/favorite/folder/tag metadata to conversations
     * - Adds attachment memory, tool audit, and reply draft tables
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE conversations ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE conversations ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE conversations ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_isArchived_updatedAt ON conversations(isArchived, updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_isFavorite_updatedAt ON conversations(isFavorite, updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_folder ON conversations(folder)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS attachment_memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversationId INTEGER,
                    messageId INTEGER,
                    uri TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    extractedText TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    embedding TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastAccessedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_memories_conversationId ON attachment_memories(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_memories_messageId ON attachment_memories(messageId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_memories_createdAt ON attachment_memories(createdAt)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tool_audits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    toolName TEXT NOT NULL,
                    riskLevel TEXT NOT NULL,
                    status TEXT NOT NULL,
                    requiresConfirmation INTEGER NOT NULL,
                    argumentsPreview TEXT NOT NULL,
                    resultPreview TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audits_toolName ON tool_audits(toolName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audits_createdAt ON tool_audits(createdAt)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reply_drafts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourcePackage TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    originalText TEXT NOT NULL,
                    draftText TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    isHandled INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reply_drafts_sourcePackage ON reply_drafts(sourcePackage)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reply_drafts_createdAt ON reply_drafts(createdAt)")
        }
    }

    /**
     * Gets all migrations for the database.
     */
    fun getAllMigrations(): Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3
    )
}
