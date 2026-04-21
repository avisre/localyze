package com.localassistant.domain.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reply_drafts",
    indices = [
        Index(value = ["sourcePackage"]),
        Index(value = ["createdAt"])
    ]
)
data class ReplyDraft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val sender: String,
    val originalText: String,
    val draftText: String = "",
    val channel: String = "notification",
    val isHandled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
