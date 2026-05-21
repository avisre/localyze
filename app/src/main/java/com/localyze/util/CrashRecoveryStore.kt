package com.localyze.util

import android.content.Context
import com.localyze.utils.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects when the previous run of the app crashed inside the LiteRT-LM
 * native layer (which is unrecoverable from Kotlin) and surfaces the
 * crashing prompt to the UI so the user isn't left staring at a dead
 * chat.
 *
 * The mechanism is a single sentinel file [SENTINEL_NAME]. Before each
 * `sendMessageAsync` call, [markInferenceStarted] writes the user prompt
 * to that file. On success / error / cancellation, [markInferenceEnded]
 * deletes it. If the file still exists on the next app launch, the
 * previous run was killed mid-inference — almost always a native crash.
 *
 * Notes:
 *  - We never report crash recovery for prompts shorter than 3 chars; those
 *    were probably the user mashing the send button on an empty input.
 *  - We add the crashing prompt to an in-memory blocklist so the same
 *    text can't be re-fired in this session — that breaks the SIGSEGV
 *    reproduction loop until the user closes/reopens the app.
 */
@Singleton
class CrashRecoveryStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sentinel: File by lazy { File(context.filesDir, SENTINEL_NAME) }
    private val gpuInitSentinel: File by lazy { File(context.filesDir, GPU_INIT_SENTINEL_NAME) }

    private val _lastCrashedPrompt = MutableStateFlow<String?>(null)
    /** Non-null on first launch after a native crash; cleared by [acknowledgeCrash]. */
    val lastCrashedPrompt: StateFlow<String?> = _lastCrashedPrompt

    private val blockedPrompts = mutableSetOf<String>()

    /**
     * True if a GPU init sentinel was found at startup — meaning the previous run
     * died (SIGABRT/SIGSEGV from the native GPU driver) inside [GemmaInferenceEngine.initialize].
     * [GemmaInferenceEngine] reads this flag and skips GPU, falling back to CPU.
     */
    @Volatile
    var gpuInitPreviouslyCrashed: Boolean = false
        private set

    init {
        // Detect a sentinel left over from the previous (killed) run.
        runCatching {
            if (sentinel.exists()) {
                val text = sentinel.readText().trim()
                if (text.length >= 3) {
                    _lastCrashedPrompt.value = text
                    blockedPrompts += text
                    AppLog.w(TAG, "Detected native crash from previous run on prompt: '${text.take(80)}'")
                }
                sentinel.delete()
            }
        }.onFailure { e ->
            AppLog.w(TAG, "CrashRecoveryStore init failed: ${e.message}")
        }

        // Detect GPU init crash: if this file exists the previous run never
        // completed initialize() cleanly (most likely a native SIGABRT in the
        // GPU driver / liblitertlm_jni.so).  We do NOT delete it here — the
        // engine will delete it after a successful initialization.
        runCatching {
            if (gpuInitSentinel.exists()) {
                gpuInitPreviouslyCrashed = true
                AppLog.w(TAG, "GPU init sentinel found — previous GPU init crashed, will force CPU backend")
            }
        }.onFailure { e ->
            AppLog.w(TAG, "GPU init sentinel check failed: ${e.message}")
        }
    }

    /** Call right before a native inference call. */
    fun markInferenceStarted(prompt: String) {
        runCatching {
            sentinel.writeText(prompt)
        }
    }

    /** Call after inference completes (success, error, OR cancellation). */
    fun markInferenceEnded() {
        runCatching { sentinel.delete() }
    }

    /** True if [prompt] has been observed to crash the model and shouldn't be retried this session. */
    fun isBlocked(prompt: String): Boolean = blockedPrompts.contains(prompt)

    /** Clear the surfaced "previous crash" banner so it doesn't show again. */
    fun acknowledgeCrash() {
        _lastCrashedPrompt.value = null
    }

    /**
     * Write the GPU init sentinel before attempting [Backend.GPU()].
     * If the process dies before [markGpuInitCleared] is called, the sentinel
     * persists and [gpuInitPreviouslyCrashed] will be true on the next launch.
     */
    fun markGpuInitStarted() {
        runCatching { gpuInitSentinel.writeText("gpu_init_started") }
    }

    /**
     * Delete the GPU init sentinel after the engine has loaded successfully
     * (regardless of which backend was ultimately used).  Must be called even
     * on CPU-fallback success so the sentinel does not linger unnecessarily.
     */
    fun markGpuInitCleared() {
        runCatching { gpuInitSentinel.delete() }
    }

    companion object {
        private const val TAG = "CrashRecoveryStore"
        private const val SENTINEL_NAME = "inference_in_progress.txt"
        private const val GPU_INIT_SENTINEL_NAME = "gpu_init_in_progress.txt"
    }
}
