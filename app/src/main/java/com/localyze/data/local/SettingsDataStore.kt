package com.localyze.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_assistant_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_THINKING_MODE = booleanPreferencesKey("thinking_mode")
        val KEY_STREAM_TOKENS = booleanPreferencesKey("stream_tokens")
        val KEY_VOICE_AUTO_PLAY = booleanPreferencesKey("voice_auto_play")
        val KEY_ALLOW_WEB_SEARCH = booleanPreferencesKey("allow_web_search")
        val KEY_PROACTIVE_ASSISTANT = booleanPreferencesKey("proactive_assistant")
        val KEY_TASK_FOLLOWUPS = booleanPreferencesKey("task_followups")
        val KEY_DAILY_SUMMARY = booleanPreferencesKey("daily_summary")
        val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        val KEY_TOP_K = intPreferencesKey("top_k")
        val KEY_ALLOW_CELLULAR_DOWNLOAD = booleanPreferencesKey("allow_cellular_download")
    }

    val darkMode: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_DARK_MODE] ?: false }
    val thinkingMode: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_THINKING_MODE] ?: true }
    val streamTokens: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_STREAM_TOKENS] ?: true }
    val voiceAutoPlay: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_VOICE_AUTO_PLAY] ?: false }
    val allowWebSearch: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ALLOW_WEB_SEARCH] ?: false }
    val proactiveAssistant: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_PROACTIVE_ASSISTANT] ?: false }
    val taskFollowups: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_TASK_FOLLOWUPS] ?: false }
    val dailySummary: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_DAILY_SUMMARY] ?: false }
    val allowCellularDownload: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ALLOW_CELLULAR_DOWNLOAD] ?: false }

    suspend fun setDarkMode(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_DARK_MODE] = value }
    }
    suspend fun setThinkingMode(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_THINKING_MODE] = value }
    }
    suspend fun setStreamTokens(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_STREAM_TOKENS] = value }
    }
    suspend fun setVoiceAutoPlay(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_VOICE_AUTO_PLAY] = value }
    }
    suspend fun setAllowWebSearch(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_ALLOW_WEB_SEARCH] = value }
    }
    suspend fun setProactiveAssistant(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_PROACTIVE_ASSISTANT] = value }
    }
    suspend fun setTaskFollowups(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_TASK_FOLLOWUPS] = value }
    }
    suspend fun setDailySummary(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_DAILY_SUMMARY] = value }
    }
    suspend fun setAllowCellularDownload(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_ALLOW_CELLULAR_DOWNLOAD] = value }
    }
}
