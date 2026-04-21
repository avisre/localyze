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

class SmsDraftTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "sms_draft"
    override val description = "Open a text message compose draft for user review. This never sends automatically."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("phone_number", buildJsonObject {
                put("type", "string")
                put("description", "Recipient phone number, if known")
            })
            put("body", buildJsonObject {
                put("type", "string")
                put("description", "Text message draft")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("body"))
        })
    }

    override suspend fun execute(args: JsonObject): String {
        val phoneNumber = args.stringArg("phone_number")
        val body = args.stringArg("body")
            ?: return errorResult("Missing required parameter: body")

        val encodedRecipient = phoneNumber?.let { Uri.encode(it) }.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$encodedRecipient")
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("message", "Text message draft opened for user review. The user must tap Send manually.")
                if (!phoneNumber.isNullOrBlank()) put("phone_number", phoneNumber)
            }.toString()
        } catch (_: ActivityNotFoundException) {
            errorResult("No messaging app is available to create the draft.")
        } catch (e: Exception) {
            errorResult("Could not open text message draft: ${e.message}")
        }
    }

    private fun JsonObject.stringArg(name: String): String? {
        return (this[name] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}
