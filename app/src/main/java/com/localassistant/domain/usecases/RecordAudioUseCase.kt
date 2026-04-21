package com.localassistant.domain.usecases

import com.localassistant.ai.AudioRecordingState
import com.localassistant.ai.SpeechToTextProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Use case that orchestrates voice input for the chat flow.
 *
 * The caller (ViewModel) will:
 * 1. Call [startRecording] when the user taps the mic button
 * 2. Observe [getRecordingState] for UI updates
 * 3. Call [stopRecording] to get the speech transcript
 * 4. Pass that transcript into the normal text model pipeline
 */
class RecordAudioUseCase @Inject constructor(
    private val speechToTextProcessor: SpeechToTextProcessor
) {

    /**
     * Start listening to speech from the microphone.
     *
     * @return [Result.success] if listening started, or [Result.failure] with an error message
     *         if permission is denied or initialization fails.
     */
    suspend fun startRecording(): Result<Unit> {
        return try {
            speechToTextProcessor.startListening()
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(SecurityException("RECORD_AUDIO permission not granted"))
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to start recording: ${e.message}", e))
        }
    }

    /**
     * Stop listening and return the recognized transcript.
     *
     * @return [Result.success] with recognized speech text, or [Result.failure] if
     *         listening was not active or no speech was recognized.
     */
    suspend fun stopRecording(): Result<String> {
        return try {
            val transcript = speechToTextProcessor.stopListening()
            if (transcript.isSuccess) {
                val text = transcript.getOrNull().orEmpty().trim()
                if (text.isBlank()) {
                    Result.failure(Exception("No speech was recognized"))
                } else {
                    Result.success(text)
                }
            } else {
                Result.failure(transcript.exceptionOrNull() ?: Exception("No speech was recognized"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to transcribe speech: ${e.message}", e))
        }
    }

    /**
     * Expose the recording state flow for UI observation.
     */
    fun getRecordingState(): StateFlow<AudioRecordingState> =
        speechToTextProcessor.recordingState

    /**
     * Expose amplitude flow for UI waveform visualization.
     * Emits values in the range 0f..1f during active speech recognition.
     */
    fun getAmplitudeFlow(): Flow<Float> =
        speechToTextProcessor.amplitudeFlow

    /**
     * Check if voice input is currently listening.
     */
    fun isRecording(): Boolean = speechToTextProcessor.isListening

    /**
     * Cancel the current voice input without returning a transcript.
     * Resets the state to Idle.
     */
    fun cancelRecording() {
        speechToTextProcessor.cancelListening()
    }
}
