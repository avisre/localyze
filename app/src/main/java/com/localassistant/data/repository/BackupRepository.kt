package com.localassistant.data.repository

import android.util.Base64
import com.localassistant.data.local.AttachmentMemoryDao
import com.localassistant.data.local.ConversationDao
import com.localassistant.data.local.MemoryDao
import com.localassistant.data.local.MessageDao
import com.localassistant.data.local.ReplyDraftDao
import com.localassistant.data.local.TaskDao
import com.localassistant.domain.models.AttachmentMemory
import com.localassistant.domain.models.Conversation
import com.localassistant.domain.models.Memory
import com.localassistant.domain.models.Message
import com.localassistant.domain.models.MessageRole
import com.localassistant.domain.models.ReplyDraft
import com.localassistant.domain.models.Task
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val taskDao: TaskDao,
    private val attachmentMemoryDao: AttachmentMemoryDao,
    private val replyDraftDao: ReplyDraftDao
) {
    companion object {
        private const val PREFIX = "LA_BACKUP_V1:"
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
    }

    suspend fun exportEncrypted(passphrase: String): String {
        require(passphrase.length >= 6) { "Use a backup passphrase with at least 6 characters." }
        val payload = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("conversations", conversationDao.getAllConversationsList().toJsonArray { it.toJson() })
            put("messages", messageDao.getAllMessagesList().toJsonArray { it.toJson() })
            put("memories", memoryDao.getAllMemoriesList().toJsonArray { it.toJson() })
            put("tasks", taskDao.getAllTasksList().toJsonArray { it.toJson() })
            put("attachments", attachmentMemoryDao.getAllList().toJsonArray { it.toJson() })
            put("replyDrafts", replyDraftDao.getAllList().toJsonArray { it.toJson() })
        }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val cipherText = encrypt(payload.toString().toByteArray(Charsets.UTF_8), passphrase, salt, iv)
        val envelope = JSONObject()
            .put("salt", salt.b64())
            .put("iv", iv.b64())
            .put("cipherText", cipherText.b64())
        return PREFIX + envelope.toString().toByteArray(Charsets.UTF_8).b64()
    }

    suspend fun importEncrypted(encodedBackup: String, passphrase: String): Int {
        require(passphrase.length >= 6) { "Use the same passphrase used for export." }
        val trimmed = encodedBackup.trim()
        require(trimmed.startsWith(PREFIX)) { "This does not look like a Localyze backup." }
        val envelopeText = String(Base64.decode(trimmed.removePrefix(PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
        val envelope = JSONObject(envelopeText)
        val plain = decrypt(
            cipherText = envelope.getString("cipherText").fromB64(),
            passphrase = passphrase,
            salt = envelope.getString("salt").fromB64(),
            iv = envelope.getString("iv").fromB64()
        )
        val payload = JSONObject(String(plain, Charsets.UTF_8))
        val conversationIdMap = mutableMapOf<Long, Long>()
        val messageIdMap = mutableMapOf<Long, Long>()

        payload.optJSONArray("conversations").forEachObjectSuspend { item ->
            val oldId = item.optLong("id")
            val newId = conversationDao.insert(item.toConversation(id = 0))
            conversationIdMap[oldId] = newId
        }

        payload.optJSONArray("messages").forEachObjectSuspend { item ->
            val oldConversationId = item.optLong("conversationId")
            val newConversationId = conversationIdMap[oldConversationId] ?: return@forEachObjectSuspend
            val oldId = item.optLong("id")
            val newId = messageDao.insert(item.toMessage(id = 0, conversationId = newConversationId))
            messageIdMap[oldId] = newId
        }

        payload.optJSONArray("memories").forEachObjectSuspend { item ->
            memoryDao.insert(item.toMemory(id = 0))
        }

        payload.optJSONArray("tasks").forEachObjectSuspend { item ->
            taskDao.insert(item.toTask(id = 0))
        }

        payload.optJSONArray("attachments").forEachObjectSuspend { item ->
            val conversationId = item.optNullableLong("conversationId")?.let { conversationIdMap[it] }
            val messageId = item.optNullableLong("messageId")?.let { messageIdMap[it] }
            attachmentMemoryDao.insert(item.toAttachment(id = 0, conversationId = conversationId, messageId = messageId))
        }

        payload.optJSONArray("replyDrafts").forEachObjectSuspend { item ->
            replyDraftDao.insert(item.toReplyDraft(id = 0))
        }

        return conversationIdMap.size
    }

    private fun encrypt(plain: ByteArray, passphrase: String, salt: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(plain)
    }

    private fun decrypt(cipherText: ByteArray, passphrase: String, salt: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(secret, "AES")
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}

private fun <T> List<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(mapper(it)) } }

private suspend fun JSONArray?.forEachObjectSuspend(block: suspend (JSONObject) -> Unit) {
    if (this == null) return
    for (index in 0 until length()) {
        val item = optJSONObject(index)
        if (item != null) block(item)
    }
}

private fun List<String>.toStringJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }
private fun List<Float>.toFloatJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it.toDouble()) } }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
}

private fun JSONArray?.toFloatList(): List<Float> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optDouble(index).toFloat() }
}

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun Conversation.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("isPinned", isPinned)
    .put("isArchived", isArchived)
    .put("isFavorite", isFavorite)
    .put("folder", folder)
    .put("tags", tags.toStringJsonArray())
    .put("summary", summary)
    .put("capabilityMode", capabilityMode)
    .put("messageCount", messageCount)

private fun JSONObject.toConversation(id: Long): Conversation = Conversation(
    id = id,
    title = optString("title", "Imported Chat"),
    createdAt = optLong("createdAt", System.currentTimeMillis()),
    updatedAt = optLong("updatedAt", System.currentTimeMillis()),
    isPinned = optBoolean("isPinned", false),
    isArchived = optBoolean("isArchived", false),
    isFavorite = optBoolean("isFavorite", false),
    folder = optString("folder", ""),
    tags = optJSONArray("tags").toStringList(),
    summary = optString("summary").takeIf { it.isNotBlank() },
    capabilityMode = optString("capabilityMode", "chat"),
    messageCount = optInt("messageCount", 0)
)

private fun Message.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("conversationId", conversationId)
    .put("role", role.name)
    .put("content", content)
    .put("thinkingContent", thinkingContent)
    .put("toolCallId", toolCallId)
    .put("toolName", toolName)
    .put("toolResult", toolResult)
    .put("imageUris", imageUris.toStringJsonArray())
    .put("audioPath", audioPath)
    .put("timestamp", timestamp)
    .put("isStreaming", isStreaming)

private fun JSONObject.toMessage(id: Long, conversationId: Long): Message = Message(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(optString("role", "USER")) }.getOrDefault(MessageRole.USER),
    content = optString("content", ""),
    thinkingContent = optString("thinkingContent").takeIf { it.isNotBlank() },
    toolCallId = optString("toolCallId").takeIf { it.isNotBlank() },
    toolName = optString("toolName").takeIf { it.isNotBlank() },
    toolResult = optString("toolResult").takeIf { it.isNotBlank() },
    imageUris = optJSONArray("imageUris").toStringList(),
    audioPath = optString("audioPath").takeIf { it.isNotBlank() },
    timestamp = optLong("timestamp", System.currentTimeMillis()),
    isStreaming = false
)

private fun Memory.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("content", content)
    .put("keywords", keywords.toStringJsonArray())
    .put("createdAt", createdAt)
    .put("lastAccessedAt", lastAccessedAt)

private fun JSONObject.toMemory(id: Long): Memory = Memory(
    id = id,
    content = optString("content", ""),
    keywords = optJSONArray("keywords").toStringList(),
    createdAt = optLong("createdAt", System.currentTimeMillis()),
    lastAccessedAt = optLong("lastAccessedAt", System.currentTimeMillis())
)

private fun Task.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("description", description)
    .put("isCompleted", isCompleted)
    .put("dueDate", dueDate)
    .put("createdAt", createdAt)

private fun JSONObject.toTask(id: Long): Task = Task(
    id = id,
    title = optString("title", "Imported task"),
    description = optString("description", ""),
    isCompleted = optBoolean("isCompleted", false),
    dueDate = optNullableLong("dueDate"),
    createdAt = optLong("createdAt", System.currentTimeMillis())
)

private fun AttachmentMemory.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("conversationId", conversationId)
    .put("messageId", messageId)
    .put("uri", uri)
    .put("displayName", displayName)
    .put("mimeType", mimeType)
    .put("extractedText", extractedText)
    .put("summary", summary)
    .put("embedding", embedding.toFloatJsonArray())
    .put("createdAt", createdAt)
    .put("lastAccessedAt", lastAccessedAt)

private fun JSONObject.toAttachment(id: Long, conversationId: Long?, messageId: Long?): AttachmentMemory =
    AttachmentMemory(
        id = id,
        conversationId = conversationId,
        messageId = messageId,
        uri = optString("uri", ""),
        displayName = optString("displayName", "Imported attachment"),
        mimeType = optString("mimeType", "application/octet-stream"),
        extractedText = optString("extractedText", ""),
        summary = optString("summary", ""),
        embedding = optJSONArray("embedding").toFloatList(),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        lastAccessedAt = optLong("lastAccessedAt", System.currentTimeMillis())
    )

private fun ReplyDraft.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("sourcePackage", sourcePackage)
    .put("sender", sender)
    .put("originalText", originalText)
    .put("draftText", draftText)
    .put("channel", channel)
    .put("isHandled", isHandled)
    .put("createdAt", createdAt)

private fun JSONObject.toReplyDraft(id: Long): ReplyDraft = ReplyDraft(
    id = id,
    sourcePackage = optString("sourcePackage", ""),
    sender = optString("sender", "Unknown"),
    originalText = optString("originalText", ""),
    draftText = optString("draftText", ""),
    channel = optString("channel", "notification"),
    isHandled = optBoolean("isHandled", false),
    createdAt = optLong("createdAt", System.currentTimeMillis())
)
