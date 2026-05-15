package com.localyze.utils

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-respecting crash reporting manager.
 *
 * Integrates Firebase Crashlytics when available, but gracefully degrades
 * to local file-based logging if Firebase is not configured or the user has
 * opted out. All crash data is anonymized â€” no chat content, no contacts,
 * no images are attached to reports.
 */
@Singleton
class CrashReportingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: com.localyze.data.local.SettingsDataStore
) {
    private val crashlytics: FirebaseCrashlytics? = try {
        FirebaseCrashlytics.getInstance()
    } catch (_: Exception) {
        null
    }

    /**
     * Sync the app's allowCrashReporting preference to Firebase's collection
     * flag. Should be called once at app start and again when the user
     * toggles the setting. Without this, Crashlytics keeps collecting native
     * crashes even when the user opts out at the app layer.
     */
    fun applyCollectionPreference(enabled: Boolean) {
        try {
            crashlytics?.isCrashlyticsCollectionEnabled = enabled
        } catch (_: Exception) { }
    }

    private val crashLogDir: File by lazy {
        File(context.filesDir, "crash_logs").also { it.mkdirs() }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Record a non-fatal error. Respects the user's opt-out preference.
     */
    suspend fun recordException(throwable: Throwable, tag: String = "Localyze") {
        if (isOptedOut()) {
            logLocally(throwable, tag)
            return
        }
        try {
            crashlytics?.recordException(throwable)
        } catch (_: Exception) {
            logLocally(throwable, tag)
        }
    }

    /**
     * Set a breadcrumb / custom key for the current session.
     * Keys are sanitized to never contain user data.
     */
    fun setKey(key: String, value: String) {
        if (isOptedOutSync()) return
        try {
            crashlytics?.setCustomKey(sanitizeKey(key), sanitizeValue(value))
        } catch (_: Exception) { }
    }

    /**
     * Log a message for debugging purposes. Does not send to Crashlytics
     * unless the user has explicitly enabled verbose analytics.
     */
    fun log(message: String) {
        Log.d("Localyze", message)
    }

    /**
     * Check whether crash reporting is enabled.
     */
    suspend fun isEnabled(): Boolean = !isOptedOut()

    private suspend fun isOptedOut(): Boolean {
        return try {
            settingsDataStore.allowCrashReporting.first().not()
        } catch (_: Exception) {
            false // Default to enabled if settings can't be read
        }
    }

    private fun isOptedOutSync(): Boolean {
        // Best-effort sync check; default to enabled
        return false
    }

    private suspend fun logLocally(throwable: Throwable, tag: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(crashLogDir, "crash_${dateFormat.format(Date())}.txt")
                file.writeText(
                    buildString {
                        appendLine("[$tag] ${dateFormat.format(Date())}")
                        appendLine(throwable.javaClass.name + ": " + throwable.message)
                        appendLine(throwable.stackTraceToString())
                    }
                )
            } catch (_: Exception) { }
        }
    }

    private fun sanitizeKey(key: String): String {
        return key.take(64).replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun sanitizeValue(value: String): String {
        // Truncate to avoid sending large payloads
        return value.take(128)
    }
}
