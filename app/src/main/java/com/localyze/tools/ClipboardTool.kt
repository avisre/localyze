package com.localyze.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "clipboard"
    override val description = "Read from or write to the device clipboard"

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "Action to perform: 'read' to get clipboard content, 'write' to set clipboard content")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("read")); add(JsonPrimitive("write"))
                })
            })
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "Text to write to clipboard (required for write action)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
        })
    }

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: action")

        return when (action) {
            "read" -> readClipboard()
            "write" -> writeClipboard(args)
            else -> errorResult("Unknown action: $action. Use 'read' or 'write'.")
        }
    }

    // â”€â”€ Read â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun readClipboard(): String {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return errorResult("ClipboardManager not available on this device")

        val clip = clipboardManager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return buildJsonObject {
                put("content", "Clipboard is empty")
                put("is_empty", true)
            }.toString()
        }

        val text = clip.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            return buildJsonObject {
                put("content", "Clipboard is empty")
                put("is_empty", true)
            }.toString()
        }

        return buildJsonObject {
            put("content", text)
            put("is_empty", false)
        }.toString()
    }

    /**
     * Clipboard write overwrites the user's clipboard, so it requires confirmation.
     */
    override fun requiresConfirmation(): Boolean = true

    // â”€â”€ Write â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun writeClipboard(args: JsonObject): String {
        val text = args["text"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: text for write action")

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return errorResult("ClipboardManager not available on this device")

        val clip = ClipData.newPlainText("text", text)
        clipboardManager.setPrimaryClip(clip)

        return buildJsonObject {
            put("success", true)
            put("message", "Copied to clipboard")
            put("length", text.length)
        }.toString()
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}