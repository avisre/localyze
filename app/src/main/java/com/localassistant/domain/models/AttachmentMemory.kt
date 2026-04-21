package com.localassistant.domain.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachment_memories",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["messageId"]),
        Index(value = ["createdAt"])
    ]
)
data class AttachmentMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val extractedText: String = "",
    val summary: String = "",
    val embedding: List<Float> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)
