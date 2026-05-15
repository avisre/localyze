package com.localyze.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.featureFlagsDataStore: DataStore<Preferences> by preferencesDataStore(name = "feature_flags")

/**
 * Lightweight feature flags manager.
 *
 * Reads defaults from a bundled `feature_flags.json` asset and supports
 * optional remote overrides fetched from a CDN or Firebase Remote Config.
 * Remote values are cached in DataStore so they survive restarts.
 *
 * To change a flag remotely, host a JSON file at [REMOTE_FLAGS_URL] with the
 * same schema as `feature_flags.json` and call [refreshFromRemote()].
 */
@Singleton
class FeatureFlagsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val dataStore = context.featureFlagsDataStore
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Current merged flags (remote overrides applied on top of defaults). */
    val flags: Flow<FeatureFlags> = dataStore.data.map { prefs ->
        val defaults = loadDefaults()
        val remoteJson = prefs[stringPreferencesKey(KEY_REMOTE_FLAGS)]
        val remote = remoteJson?.let {
            try { json.decodeFromString(it) } catch (_: Exception) { null }
        }
        defaults.mergeWith(remote)
    }

    suspend fun getFlags(): FeatureFlags = flags.first()

    /** Refresh remote overrides. Safe to call from a WorkManager or app startup. */
    suspend fun refreshFromRemote(): Result<Unit> {
        return try {
            val request = Request.Builder()
                .url(REMOTE_FLAGS_URL)
                .header("Accept", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(IOException("Remote flags returned ${response.code}"))
            }
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            // Validate JSON before storing
            json.decodeFromString<FeatureFlags>(body)
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(KEY_REMOTE_FLAGS)] = body
                prefs[booleanPreferencesKey(KEY_LAST_FETCH_SUCCESS)] = true
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Reset remote overrides back to bundled defaults. */
    suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(KEY_REMOTE_FLAGS))
            prefs[booleanPreferencesKey(KEY_LAST_FETCH_SUCCESS)] = false
        }
    }

    private fun loadDefaults(): FeatureFlags {
        return try {
            context.assets.open("feature_flags.json").bufferedReader().use {
                json.decodeFromString(it.readText())
            }
        } catch (_: Exception) {
            FeatureFlags() // fallback to hard-coded defaults
        }
    }

    companion object {
        private const val KEY_REMOTE_FLAGS = "remote_flags_json"
        private const val KEY_LAST_FETCH_SUCCESS = "remote_flags_last_success"

        /**
         * URL for remote feature flags. Replace with your own CDN endpoint
         * before production shipping. When empty, remote fetching is disabled.
         */
        private const val REMOTE_FLAGS_URL = ""
    }
}

@Serializable
data class FeatureFlags(
    val enableWebSearch: Boolean = true,
    val enableNotificationReplies: Boolean = true,
    val enableCrashReporting: Boolean = true,
    val enableMemorySystem: Boolean = true,
    val enableToolConfirmations: Boolean = true,
    val enableBilling: Boolean = true,
    val enableVoiceInput: Boolean = true,
    val maxContextWindowTokens: Int = 10000,
    val modelDownloadUrlOverride: String = "",
    val webSearchTimeoutMs: Long = 12000,
    val enableChartVisualizations: Boolean = true,
    val enableCodeWorkspace: Boolean = true,
    val enablePerChatMemory: Boolean = true,
    val enableLongTermMemory: Boolean = true,
    val enableFinancialDataSearch: Boolean = true
) {
    fun mergeWith(other: FeatureFlags?): FeatureFlags {
        if (other == null) return this
        return FeatureFlags(
            enableWebSearch = if (other.enableWebSearch != default.enableWebSearch) other.enableWebSearch else enableWebSearch,
            enableNotificationReplies = if (other.enableNotificationReplies != default.enableNotificationReplies) other.enableNotificationReplies else enableNotificationReplies,
            enableCrashReporting = if (other.enableCrashReporting != default.enableCrashReporting) other.enableCrashReporting else enableCrashReporting,
            enableMemorySystem = if (other.enableMemorySystem != default.enableMemorySystem) other.enableMemorySystem else enableMemorySystem,
            enableToolConfirmations = if (other.enableToolConfirmations != default.enableToolConfirmations) other.enableToolConfirmations else enableToolConfirmations,
            enableBilling = if (other.enableBilling != default.enableBilling) other.enableBilling else enableBilling,
            enableVoiceInput = if (other.enableVoiceInput != default.enableVoiceInput) other.enableVoiceInput else enableVoiceInput,
            maxContextWindowTokens = if (other.maxContextWindowTokens != default.maxContextWindowTokens) other.maxContextWindowTokens else maxContextWindowTokens,
            modelDownloadUrlOverride = other.modelDownloadUrlOverride.takeIf { it.isNotBlank() } ?: modelDownloadUrlOverride,
            webSearchTimeoutMs = if (other.webSearchTimeoutMs != default.webSearchTimeoutMs) other.webSearchTimeoutMs else webSearchTimeoutMs,
            enableChartVisualizations = if (other.enableChartVisualizations != default.enableChartVisualizations) other.enableChartVisualizations else enableChartVisualizations,
            enableCodeWorkspace = if (other.enableCodeWorkspace != default.enableCodeWorkspace) other.enableCodeWorkspace else enableCodeWorkspace,
            enablePerChatMemory = if (other.enablePerChatMemory != default.enablePerChatMemory) other.enablePerChatMemory else enablePerChatMemory,
            enableLongTermMemory = if (other.enableLongTermMemory != default.enableLongTermMemory) other.enableLongTermMemory else enableLongTermMemory,
            enableFinancialDataSearch = if (other.enableFinancialDataSearch != default.enableFinancialDataSearch) other.enableFinancialDataSearch else enableFinancialDataSearch
        )
    }

    companion object {
        private val default = FeatureFlags()
    }
}
