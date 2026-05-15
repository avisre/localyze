package com.localyze

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.localyze.data.local.SettingsDataStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class LocalyzeApp : Application() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureModelsDirectory()
        initializeFirebase()
    }

    private fun ensureModelsDirectory() {
        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    private fun initializeFirebase() {
        try {
            FirebaseApp.initializeApp(this)
            // Observe the user's opt-out preference so toggling the setting
            // takes effect immediately, not only on next launch.
            appScope.launch {
                try {
                    settingsDataStore.allowCrashReporting
                        .distinctUntilChanged()
                        .collect { enabled ->
                            FirebaseCrashlytics.getInstance()
                                .setCrashlyticsCollectionEnabled(enabled)
                        }
                } catch (_: Exception) {
                    FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                }
            }
        } catch (_: Exception) {
            // Firebase not configured — crash reporting disabled
        }
    }
}