package com.localyze.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Converts the chat microphone input into text using Android's speech service.
 *
 * The chat flow needs a transcript because the local Gemma text conversation path
 * is the reliable route for normal voice commands. Once speech is transcribed,
 * ChatViewModel sends the transcript through the same sendMessage() pipeline used
 * by typed prompts.
 */
@Singleton
class SpeechToTextProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_LISTENING_MS = 30_000L
        private const val STOP_RESULT_TIMEOUT_MS = 7_000L
        private const val STATE_RESET_DELAY_MS = 700L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _recordingState = MutableStateFlow<AudioRecordingState>(AudioRecordingState.Idle)
    val recordingState: StateFlow<AudioRecordingState> = _recordingState.asStateFlow()

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: Flow<Float> = _amplitudeFlow

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionResult: CompletableDeferred<String>? = null
    private var elapsedJob: Job? = null
    private var resetJob: Job? = null
    private var startedAtMs: Long = 0L
    private var lastAmplitude: Float = 0f

    @Volatile
    private var latestPartialTranscript: String = ""

    @Volatile
    private var stoppedByUser: Boolean = false

    val isListening: Boolean
        get() = _recordingState.value is AudioRecordingState.Recording

    suspend fun startListening() = withContext(Dispatchers.Main.immediate) {
        android.util.Log.d("SpeechToTextProcessor", "startListening() called, currentState=${_recordingState.value}")
        if (isListening) {
            android.util.Log.d("SpeechToTextProcessor", "Already listening, throwing")
            throw IllegalStateException("Voice input is already listening")
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showErrorThenReset("Microphone permission is not granted")
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            showErrorThenReset("Speech recognition is not available on this device")
            throw IllegalStateException("Speech recognition is not available")
        }

        destroyRecognizer()
        resetJob?.cancel()
        stoppedByUser = false
        latestPartialTranscript = ""
        lastAmplitude = 0f
        startedAtMs = System.currentTimeMillis()
        recognitionResult = CompletableDeferred()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
                startListening(createRecognizerIntent())
            }
            android.util.Log.d("SpeechToTextProcessor", "SpeechRecognizer started successfully")
        } catch (e: Exception) {
            android.util.Log.e("SpeechToTextProcessor", "SpeechRecognizer failed to start", e)
            showErrorThenReset("Speech recognizer failed: ${e.message}")
            throw e
        }

        _amplitudeFlow.value = 0f
        _recordingState.value = AudioRecordingState.Recording(elapsedSeconds = 0f, amplitude = 0f)
        android.util.Log.d("SpeechToTextProcessor", "State set to Recording")
        startElapsedTicker()
    }

    suspend fun stopListening(): Result<String> {
        val deferred = recognitionResult
            ?: return Result.failure(IllegalStateException("Voice input is not active"))

        withContext(Dispatchers.Main.immediate) {
            stoppedByUser = true
            speechRecognizer?.stopListening()
        }

        val transcript = withTimeoutOrNull(STOP_RESULT_TIMEOUT_MS) {
            deferred.await()
        }?.trim()

        if (!transcript.isNullOrBlank()) {
            withContext(Dispatchers.Main.immediate) {
                showReadyThenReset(transcript)
            }
            return Result.success(transcript)
        }

        val partial = latestPartialTranscript.trim()
        if (partial.isNotBlank()) {
            withContext(Dispatchers.Main.immediate) {
                completeWithTranscript(partial, showReady = true)
            }
            return Result.success(partial)
        }

        withContext(Dispatchers.Main.immediate) {
            recognitionResult?.takeIf { !it.isCompleted }?.complete("")
            speechRecognizer?.cancel()
            destroyRecognizer()
            showErrorThenReset("No speech was recognized")
        }
        return Result.failure(IllegalStateException("No speech was recognized"))
    }

    fun cancelListening() {
        scope.launch {
            stoppedByUser = true
            recognitionResult?.takeIf { !it.isCompleted }?.complete("")
            speechRecognizer?.cancel()
            destroyRecognizer()
            latestPartialTranscript = ""
            _amplitudeFlow.value = 0f
            _recordingState.value = AudioRecordingState.Idle
        }
    }

    private fun createRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }

    private fun createRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() {
                updateRecordingState()
            }

            override fun onRmsChanged(rmsdB: Float) {
                lastAmplitude = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _amplitudeFlow.value = lastAmplitude
                updateRecordingState()
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                updateRecordingState()
            }

            override fun onError(error: Int) {
                android.util.Log.d("SpeechToTextProcessor", "onError called: error=$error, message=${speechErrorMessage(error)}")
                val partial = latestPartialTranscript.trim()
                if (stoppedByUser && partial.isNotBlank()) {
                    completeWithTranscript(partial, showReady = true)
                    return
                }

                val message = speechErrorMessage(error)
                recognitionResult?.takeIf { !it.isCompleted }?.complete("")
                destroyRecognizer()
                showErrorThenReset(message)
            }

            override fun onResults(results: Bundle?) {
                android.util.Log.d("SpeechToTextProcessor", "onResults called: transcript='${bestTranscript(results)}'")
                completeWithTranscript(bestTranscript(results), showReady = stoppedByUser)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                latestPartialTranscript = bestTranscript(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun bestTranscript(bundle: Bundle?): String {
        val matches = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        return matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun completeWithTranscript(transcript: String, showReady: Boolean) {
        val cleaned = transcript.trim()
        recognitionResult?.takeIf { !it.isCompleted }?.complete(cleaned)
        destroyRecognizer()

        if (cleaned.isBlank()) {
            showErrorThenReset("No speech was recognized")
            return
        }

        if (showReady) {
            showReadyThenReset(cleaned)
        }
    }

    private fun showReadyThenReset(transcript: String) {
        android.util.Log.d("SpeechToTextProcessor", "showReadyThenReset called")
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        _amplitudeFlow.value = 0f
        _recordingState.value = AudioRecordingState.Ready(
            audioData = transcript.toByteArray(),
            durationMs = elapsedMs
        )
        resetToIdleSoon()
    }

    private fun updateRecordingState() {
        if (_recordingState.value !is AudioRecordingState.Recording) return
        val elapsedSec = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L) / 1000f
        _recordingState.value = AudioRecordingState.Recording(
            elapsedSeconds = elapsedSec,
            amplitude = lastAmplitude
        )
    }

    private fun startElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = scope.launch {
            while (_recordingState.value is AudioRecordingState.Recording) {
                updateRecordingState()
                if (System.currentTimeMillis() - startedAtMs >= MAX_LISTENING_MS) {
                    stoppedByUser = true
                    speechRecognizer?.stopListening()
                    break
                }
                delay(100L)
            }
        }
    }

    private fun showErrorThenReset(message: String) {
        android.util.Log.d("SpeechToTextProcessor", "showErrorThenReset called: message=$message")
        _amplitudeFlow.value = 0f
        _recordingState.value = AudioRecordingState.Error(message)
        resetToIdleSoon()
    }

    private fun resetToIdleSoon() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(STATE_RESET_DELAY_MS)
            android.util.Log.d("SpeechToTextProcessor", "resetToIdleSoon fired, setting state to Idle")
            _recordingState.value = AudioRecordingState.Idle
        }
    }

    private fun destroyRecognizer() {
        elapsedJob?.cancel()
        elapsedJob = null
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun speechErrorMessage(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed"
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not granted"
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard"
            else -> "Speech recognition failed"
        }
}
