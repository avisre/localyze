package com.localassistant.data.repository

import com.localassistant.data.local.ToolAuditDao
import com.localassistant.domain.models.ToolAudit
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolAuditRepository @Inject constructor(
    private val toolAuditDao: ToolAuditDao
) {
    fun getRecent(limit: Int = 100): Flow<List<ToolAudit>> = toolAuditDao.getRecent(limit)

    suspend fun record(
        toolName: String,
        riskLevel: String,
        status: String,
        requiresConfirmation: Boolean,
        argumentsPreview: String,
        resultPreview: String = ""
    ) {
        toolAuditDao.insert(
            ToolAudit(
                toolName = toolName,
                riskLevel = riskLevel,
                status = status,
                requiresConfirmation = requiresConfirmation,
                argumentsPreview = argumentsPreview.take(500),
                resultPreview = resultPreview.take(500)
            )
        )
    }

    suspend fun clear() {
        toolAuditDao.clear()
    }
}
