package com.localassistant.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.localassistant.data.local.AttachmentMemoryDao
import com.localassistant.domain.models.AttachmentMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentMemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentMemoryDao: AttachmentMemoryDao
) {
    fun getAll(): Flow<List<AttachmentMemory>> = attachmentMemoryDao.getAll()

    fun search(query: String): Flow<List<AttachmentMemory>> = attachmentMemoryDao.search(query)

    suspend fun addFromUri(uri: Uri, conversationId: Long? = null, messageId: Long? = null): AttachmentMemory {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers from ACTION_GET_CONTENT do not grant persistable permissions.
        }

        val displayName = getDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val extractedText = extractText(uri, mimeType)
        val summary = summarize(displayName, mimeType, extractedText)
        val embedding = EmbeddingUtils.embed("$displayName $mimeType $summary $extractedText")

        val attachment = AttachmentMemory(
            conversationId = conversationId,
            messageId = messageId,
            uri = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            extractedText = extractedText,
            summary = summary,
            embedding = embedding
        )
        val id = attachmentMemoryDao.insert(attachment)
        return attachment.copy(id = id)
    }

    suspend fun delete(id: Long) {
        attachmentMemoryDao.deleteById(id)
    }

    suspend fun semanticSearch(query: String): List<AttachmentMemory> {
        val queryVector = EmbeddingUtils.embed(query)
        return attachmentMemoryDao.getAllList()
            .map { attachment -> attachment to EmbeddingUtils.cosine(queryVector, attachment.embedding) }
            .filter { (_, score) -> score > 0.05f }
            .sortedByDescending { (_, score) -> score }
            .map { it.first }
    }

    private fun getDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Attachment"
    }

    private fun extractText(uri: Uri, mimeType: String): String {
        val isPlainText = mimeType.startsWith("text/") ||
            mimeType == "application/json" ||
            mimeType == "application/xml" ||
            mimeType == "application/x-markdown"
        if (!isPlainText) return ""
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText().take(50_000)
            }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun summarize(displayName: String, mimeType: String, extractedText: String): String {
        if (extractedText.isBlank()) {
            return when {
                mimeType.startsWith("image/") -> "Image attachment saved for later visual chat and reference."
                mimeType == "application/pdf" -> "PDF attachment saved. Text extraction can be added with a PDF parser."
                else -> "Attachment saved for later reference."
            }
        }
        val firstSentence = extractedText
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        return "$displayName: $firstSentence"
    }
}
