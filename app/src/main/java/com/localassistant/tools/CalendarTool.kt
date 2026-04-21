package com.localassistant.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class CalendarTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "calendar"
    override val description = "Read events from or create events in the user's calendar"

    // ── Schema ─────────────────────────────────────────────────────────────

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "Action to perform: 'read' to query events, 'write' to create an event")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("read")); add(JsonPrimitive("write"))
                })
            })
            put("start_date", buildJsonObject {
                put("type", "string")
                put("description", "Start date/time in ISO format (e.g. '2025-01-15' or '2025-01-15T09:00:00')")
            })
            put("end_date", buildJsonObject {
                put("type", "string")
                put("description", "End date/time in ISO format (e.g. '2025-01-15' or '2025-01-15T10:00:00')")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of events to return (default 10, for read action)")
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "Event title (required for write action)")
            })
            put("location", buildJsonObject {
                put("type", "string")
                put("description", "Event location (optional, for write action)")
            })
            put("description_event", buildJsonObject {
                put("type", "string")
                put("description", "Event description (optional, for write action)")
            })
            put("calendar_id", buildJsonObject {
                put("type", "integer")
                put("description", "Calendar ID to write to (optional, defaults to first writable calendar)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("action")); add(JsonPrimitive("start_date")); add(JsonPrimitive("end_date"))
        })
    }

    // ── Execute ────────────────────────────────────────────────────────────

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: action")

        return when (action) {
            "read" -> readEvents(args)
            "write" -> writeEvent(args)
            else -> errorResult("Unknown action: $action. Use 'read' or 'write'.")
        }
    }

    // ── Read ────────────────────────────────────────────────────────────────

    private fun readEvents(args: JsonObject): String {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return errorResult("READ_CALENDAR permission not granted. Please grant calendar permission in Settings.")
        }

        val startDateStr = args["start_date"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: start_date")
        val endDateStr = args["end_date"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: end_date")
        val maxResults = args["max_results"]?.let { (it as JsonPrimitive).content?.toIntOrNull() } ?: 10

        val startMillis = parseDateToMillis(startDateStr)
            ?: return errorResult("Invalid start_date format: $startDateStr. Use ISO format like '2025-01-15' or '2025-01-15T09:00:00'.")
        val endMillis = parseDateToMillis(endDateStr)
            ?: return errorResult("Invalid end_date format: $endDateStr. Use ISO format like '2025-01-15' or '2025-01-15T10:00:00'.")

        val events = mutableListOf<JsonObject>()

        try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)
            val uri = builder.build()

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )

            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < maxResults) {
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val locationIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                    val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                    val calNameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

                    val title = cursor.getString(titleIdx) ?: ""
                    val begin = cursor.getLong(beginIdx)
                    val end = cursor.getLong(endIdx)
                    val location = cursor.getString(locationIdx) ?: ""
                    val description = cursor.getString(descIdx) ?: ""
                    val calendarName = cursor.getString(calNameIdx) ?: ""

                    events.add(buildJsonObject {
                        put("title", title)
                        put("start", formatMillis(begin))
                        put("end", formatMillis(end))
                        put("location", location)
                        put("description", description)
                        put("calendar_name", calendarName)
                    })
                    count++
                }
            }
        } catch (e: SecurityException) {
            return errorResult("Permission denied: ${e.message}. Please grant READ_CALENDAR permission.")
        } catch (e: Exception) {
            return errorResult("Error reading calendar: ${e.message}")
        }

        return buildJsonObject {
            put("events", JsonArray(events))
            put("count", events.size)
        }.toString()
    }

    // ── Write ──────────────────────────────────────────────────────────────

    private fun writeEvent(args: JsonObject): String {
        val title = args["title"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: title for write action")
        val startDateStr = args["start_date"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: start_date")
        val endDateStr = args["end_date"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: end_date")

        val startMillis = parseDateToMillis(startDateStr)
            ?: return errorResult("Invalid start_date format: $startDateStr")
        val endMillis = parseDateToMillis(endDateStr)
            ?: return errorResult("Invalid end_date format: $endDateStr")

        val location = args["location"]?.let { (it as JsonPrimitive).content } ?: ""
        val description = args["description_event"]?.let { (it as JsonPrimitive).content } ?: ""

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("title", title)
                put("start", formatMillis(startMillis))
                put("end", formatMillis(endMillis))
                put("message", "Calendar event draft opened for user review. The user must save it manually.")
            }.toString()
        } catch (_: ActivityNotFoundException) {
            errorResult("No calendar app is available to create the event draft.")
        } catch (e: SecurityException) {
            errorResult("Permission denied: ${e.message}. Please grant calendar permission.")
        } catch (e: Exception) {
            errorResult("Error opening calendar event draft: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                }
            }
        } catch (_: Exception) { }

        // Fallback: try to get any calendar
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                }
            }
        } catch (_: Exception) { }

        return null
    }

    private fun parseDateToMillis(dateStr: String): Long? {
        return try {
            // Try full ISO datetime first
            try {
                val ldt = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Try date only
                val ld = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
            // Try ZonedDateTime
            try {
                ZonedDateTime.parse(dateStr).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun formatMillis(millis: Long): String {
        return try {
            val zdt = java.time.Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
            zdt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: Exception) {
            millis.toString()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}
