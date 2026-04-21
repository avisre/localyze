package com.localyze.domain.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_audits",
    indices = [
        Index(value = ["toolName"]),
        Index(value = ["createdAt"])
    ]
)
data class ToolAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolName: String,
    val riskLevel: String,
    val status: String,
    val requiresConfirmation: Boolean,
    val argumentsPreview: String = "",
    val resultPreview: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
