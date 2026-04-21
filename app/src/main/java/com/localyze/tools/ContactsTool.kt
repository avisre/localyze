package com.localyze.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class ContactsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "contacts_search"
    override val description = "Search the user's contacts by name, returning name, phone numbers, and email addresses"

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query to match against contact names")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of contacts to return (default 5)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("query"))
        })
    }

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return errorResult("READ_CONTACTS permission not granted. Please grant contacts permission in Settings.")
        }

        val query = args["query"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: query")
        val maxResults = args["max_results"]?.let { (it as JsonPrimitive).content?.toIntOrNull() } ?: 5

        val contacts = mutableListOf<JsonObject>()

        try {
            // Search contacts by display name using LIKE
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )

            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < maxResults) {
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)

                    val contactId = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: ""

                    val phones = getPhoneNumbers(contactId)
                    val emails = getEmailAddresses(contactId)

                    contacts.add(buildJsonObject {
                        put("name", name)
                        put("phones", JsonArray(phones.map { JsonPrimitive(it) }))
                        put("emails", JsonArray(emails.map { JsonPrimitive(it) }))
                    })
                    count++
                }
            }
        } catch (e: SecurityException) {
            return errorResult("Permission denied: ${e.message}. Please grant READ_CONTACTS permission.")
        } catch (e: Exception) {
            return errorResult("Error searching contacts: ${e.message}")
        }

        return buildJsonObject {
            put("contacts", JsonArray(contacts))
            put("count", contacts.size)
        }.toString()
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun getPhoneNumbers(contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    )
                    if (number.isNotBlank()) phones.add(number)
                }
            }
        } catch (_: Exception) { }
        return phones
    }

    private fun getEmailAddresses(contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val email = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    )
                    if (email.isNotBlank()) emails.add(email)
                }
            }
        } catch (_: Exception) { }
        return emails
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}