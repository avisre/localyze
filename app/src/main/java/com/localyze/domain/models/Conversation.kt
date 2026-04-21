package com.localyze.domain.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["isPinned", "updatedAt"]),
        Index(value = ["isArchived", "updatedAt"]),
        Index(value = ["isFavorite", "updatedAt"]),
        Index(value = ["folder"])
    ]
)
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "''") val folder: String = "",
    @ColumnInfo(defaultValue = "''") val tags: List<String> = emptyList(),
    val summary: String? = null,
    val capabilityMode: String = "chat",
    val messageCount: Int = 0
)
