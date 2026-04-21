package com.localassistant.domain.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["role"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val thinkingContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolResult: String? = null,
    val imageUris: List<String> = emptyList(),
    val audioPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

class MessageRoleConverter {
    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(name: String): MessageRole = MessageRole.valueOf(name)
}

class StringListConverter {
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(separator = "|||")

    @TypeConverter
    fun toStringList(joined: String): List<String> =
        if (joined.isEmpty()) emptyList() else joined.split("|||")
}

class FloatListConverter {
    @TypeConverter
    fun fromFloatList(list: List<Float>): String = list.joinToString(separator = ",")

    @TypeConverter
    fun toFloatList(joined: String): List<Float> =
        if (joined.isEmpty()) emptyList() else joined.split(",").mapNotNull { it.toFloatOrNull() }
}
