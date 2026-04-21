package com.localassistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class EmailDraftTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "email_draft"
    override val description = "Open an email compose draft for user review. This never sends automatically."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("to", buildJsonObject {
                put("type", "string")
                put("description", "Recipient email address, if known")
            })
            put("subject", buildJsonObject {
                put("type", "string")
                put("description", "Email subject")
            })
            put("body", buildJsonObject {
                put("type", "string")
                put("description", "Email body draft")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("body"))
        })
    }

    override suspend fun execute(args: JsonObject): String {
        val to = args.stringArg("to")
        val subject = args.stringArg("subject").orEmpty()
        val body = args.stringArg("body")
            ?: return errorResult("Missing required parameter: body")

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (!to.isNullOrBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            }
            if (subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("message", "Email draft opened for user review. The user must tap Send manually.")
                if (!to.isNullOrBlank()) put("to", to)
                if (subject.isNotBlank()) put("subject", subject)
            }.toString()
        } catch (_: ActivityNotFoundException) {
            errorResult("No email app is available to create the draft.")
        } catch (e: Exception) {
            errorResult("Could not open email draft: ${e.message}")
        }
    }

    private fun JsonObject.stringArg(name: String): String? {
        return (this[name] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}
