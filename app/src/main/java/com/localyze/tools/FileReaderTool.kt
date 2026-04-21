package com.localyze.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class FileReaderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "file_read"
    override val description = "Read text content from a file selected by the user. Supports .txt and .md files."

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("txt", "md", "text", "markdown", "csv", "json", "xml", "log", "html", "css", "js", "kt", "java", "py")
    }

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("file_uri", buildJsonObject {
                put("type", "string")
                put("description", "URI of the file to read (from document picker)")
            })
            put("max_chars", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum characters to read from the file (default 4000)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("file_uri"))
        })
    }

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        val fileUriStr = args["file_uri"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: file_uri")
        val maxChars = args["max_chars"]?.let { (it as JsonPrimitive).content?.toIntOrNull() } ?: 4000

        val uri = try {
            Uri.parse(fileUriStr)
        } catch (e: Exception) {
            return errorResult("Invalid file URI: $fileUriStr")
        }

        // Validate file extension
        val fileName = getFileName(uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty() && extension !in SUPPORTED_EXTENSIONS) {
            return errorResult("Unsupported file type: .$extension. Supported types: ${SUPPORTED_EXTENSIONS.joinToString(", ")}")
        }

        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return errorResult("Cannot open file. The file may have been moved or deleted.")

            val content = inputStream.use { stream ->
                val bytes = stream.readBytes()
                val fullText = String(bytes, Charsets.UTF_8)

                if (fullText.length > maxChars) {
                    val truncated = fullText.substring(0, maxChars)
                    val remaining = fullText.length - maxChars
                    "$truncated\n\n... [truncated, $remaining more characters]"
                } else {
                    fullText
                }
            }

            buildJsonObject {
                put("content", content)
                put("file_name", fileName)
                put("length", content.length)
                put("truncated", content.contains("[truncated"))
            }.toString()
        } catch (e: SecurityException) {
            errorResult("Permission denied: Cannot access file. Please grant permission to access the file.")
        } catch (e: java.io.FileNotFoundException) {
            errorResult("File not found: $fileUriStr. The file may have been moved or deleted.")
        } catch (e: Exception) {
            errorResult("Error reading file: ${e.message}")
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun getFileName(uri: Uri): String {
        // Try to get the display name from the content resolver
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex) ?: ""
                    }
                }
            }
        } catch (_: Exception) { }

        // Fallback: extract from URI path
        return uri.lastPathSegment ?: uri.path?.substringAfterLast('/') ?: "unknown"
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}