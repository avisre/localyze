package com.localyze

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.localyze.ai.GemmaInferenceEngine
import com.localyze.data.local.SettingsDataStore
import com.localyze.utils.AppLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    @Inject
    lateinit var gemmaInferenceEngine: GemmaInferenceEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Pending background-release job. Started in ON_STOP, cancelled in
     * ON_START if the user returns to the app within the 30s grace window.
     * Volatile so cancellation from the main thread (ON_START) reliably
     * sees the latest reference assigned from the background dispatcher.
     */
    @Volatile
    private var pendingBackgroundReleaseJob: Job? = null

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // App went to background. Wait 30s before releasing — if the
            // user briefly switches apps and comes back, we don't want to
            // thrash the multi-GB engine reload.
            pendingBackgroundReleaseJob?.cancel()
            pendingBackgroundReleaseJob = appScope.launch {
                try {
                    delay(BACKGROUND_RELEASE_DELAY_MS)
                    AppLog.d(TAG, "Background grace expired — releasing GPU memory")
                    gemmaInferenceEngine.releaseForBackground()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // User returned within grace window — nothing to do.
                }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground. Cancel pending release.
            pendingBackgroundReleaseJob?.cancel()
            pendingBackgroundReleaseJob = null
            // If we already released, re-initialize in the background.
            // initialize() is idempotent: it short-circuits when the load
            // state is already Loaded, and starts a fresh engine otherwise.
            if (!gemmaInferenceEngine.isModelLoaded()) {
                appScope.launch {
                    try {
                        AppLog.d(TAG, "Returning to foreground — re-initializing engine")
                        gemmaInferenceEngine.initialize()
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Foreground re-initialization failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureModelsDirectory()
        initializeFirebase()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // At TRIM_MEMORY_RUNNING_LOW or higher, the OS is under memory pressure
        // (its memory_leak_event warning threshold). Drop the engine immediately
        // — no 30s grace — to avoid an OOM kill.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            AppLog.w(TAG, "onTrimMemory level=$level — releasing engine immediately")
            pendingBackgroundReleaseJob?.cancel()
            pendingBackgroundReleaseJob = appScope.launch {
                try {
                    gemmaInferenceEngine.releaseForBackground()
                } catch (e: Exception) {
                    AppLog.w(TAG, "Immediate release failed: ${e.message}")
                }
            }
        }
    }

    private fun ensureModelsDirectory() {
        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) { modelsDir.mkdirs() }
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

    companion object {
        private const val TAG = "LocalyzeApp"
        private const val BACKGROUND_RELEASE_DELAY_MS = 30_000L
    }
}
