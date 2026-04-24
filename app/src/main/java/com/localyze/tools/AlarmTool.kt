package com.localyze.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

class AlarmTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "alarm_set"
    override val description = "Set an alarm or reminder notification at a specified time"

    companion object {
        /** Action used by the BroadcastReceiver that displays the alarm notification. */
        const val ACTION_ALARM_TRIGGER = "com.localyze.ALARM_TRIGGER"

        /** Extra key for the alarm message. */
        const val EXTRA_ALARM_MESSAGE = "alarm_message"

        /** Extra key for the alarm ID. */
        const val EXTRA_ALARM_ID = "alarm_id"

        /** Extra key for the repeat mode. */
        const val EXTRA_REPEAT = "alarm_repeat"

        /** Extra key for the scheduled trigger time. */
        const val EXTRA_TRIGGER_TIME = "alarm_trigger_time"

        /**
         * Fully qualified class name of the BroadcastReceiver that will handle
         * alarm triggers. This class will be created in a later step.
         * Declared here so the Intent can target it explicitly.
         */
        const val ALARM_RECEIVER_CLASS = "com.localyze.receivers.AlarmReceiver"

        /** Request code base for alarm PendingIntents. */
        const val REQUEST_CODE_BASE = 0x1A2B
    }

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("message", buildJsonObject {
                put("type", "string")
                put("description", "The alarm or reminder message to display")
            })
            put("time", buildJsonObject {
                put("type", "string")
                put("description", "ISO datetime string for when the alarm should trigger (e.g. '2025-01-15T09:00:00')")
            })
            put("repeat", buildJsonObject {
                put("type", "string")
                put("description", "Repeat mode: 'none', 'daily', or 'weekly' (default 'none')")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("none")); add(JsonPrimitive("daily")); add(JsonPrimitive("weekly"))
                })
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("message")); add(JsonPrimitive("time"))
        })
    }

    // â”€â”€ Execute â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override suspend fun execute(args: JsonObject): String {
        val message = args["message"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: message")
        val timeStr = args["time"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: time")
        val repeat = args["repeat"]?.let { (it as JsonPrimitive).content } ?: "none"

        val triggerTime = parseTime(timeStr)
            ?: return errorResult("Invalid time format: $timeStr. Use ISO datetime like '2025-01-15T09:00:00'.")

        val now = System.currentTimeMillis()
        if (triggerTime <= now) {
            return errorResult("The specified time is in the past. Please provide a future time.")
        }

        val alarmId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        return try {
            val pendingIntent = createAlarmPendingIntent(alarmId, message, repeat, triggerTime)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val repeatIntervalMs = when (repeat) {
                "daily" -> AlarmManager.INTERVAL_DAY
                "weekly" -> AlarmManager.INTERVAL_DAY * 7
                else -> 0L
            }

            if (repeatIntervalMs > 0L) {
                // Repeating alarm â€” use setRepeating for daily/weekly,
                // but on M+ we prefer exact + reschedule via the receiver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        repeatIntervalMs,
                        pendingIntent
                    )
                }
            } else {
                // One-shot exact alarm
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!alarmManager.canScheduleExactAlarms()) {
                        // Fallback: use inexact alarm
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        return buildJsonObject {
                            put("success", true)
                            put("alarm_id", alarmId)
                            put("message", message)
                            put("trigger_time", formatMillis(triggerTime))
                            put("repeat", repeat)
                            put("exact", false)
                            put("warning", "Exact alarms not permitted. Using inexact alarm - may fire slightly later than requested time.")
                        }.toString()
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            }

            buildJsonObject {
                put("success", true)
                put("alarm_id", alarmId)
                put("message", message)
                put("trigger_time", formatMillis(triggerTime))
                put("repeat", repeat)
                put("exact", true)
            }.toString()
        } catch (e: SecurityException) {
            errorResult("Permission denied: ${e.message}. SCHEDULE_EXACT_ALARM permission may be required.")
        } catch (e: Exception) {
            errorResult("Error setting alarm: ${e.message}")
        }
    }

    // â”€â”€ PendingIntent builder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Creates a PendingIntent targeting the placeholder AlarmReceiver.
     *
     * The actual BroadcastReceiver class ([ALARM_RECEIVER_CLASS]) will be
     * created in a later step. The Intent uses an explicit component so
     * the PendingIntent survives app process death. If the receiver class
     * does not exist yet, the alarm will still be scheduled but the
     * broadcast will fail silently until the receiver is implemented.
     */
    private fun createAlarmPendingIntent(
        alarmId: Int,
        message: String,
        repeat: String,
        triggerTime: Long
    ): PendingIntent {
        val intent = Intent(ACTION_ALARM_TRIGGER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ALARM_MESSAGE, message)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_REPEAT, repeat)
            putExtra(EXTRA_TRIGGER_TIME, triggerTime)
        }

        // Try to set the explicit component class; if the receiver doesn't
        // exist yet, fall back to an action-only implicit broadcast.
        try {
            val receiverClass = Class.forName(ALARM_RECEIVER_CLASS)
            intent.setClass(context, receiverClass)
        } catch (_: ClassNotFoundException) {
            // Receiver not yet created â€” the alarm will be scheduled but
            // the broadcast won't be received until it's implemented.
            // Using explicit package to scope the implicit broadcast.
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun parseTime(timeStr: String): Long? {
        return try {
            ZonedDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                val ldt = LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun formatMillis(millis: Long): String {
        return try {
            java.time.Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: Exception) {
            millis.toString()
        }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}
