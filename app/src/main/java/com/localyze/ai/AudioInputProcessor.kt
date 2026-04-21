package com.localyze.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core audio recording processor that captures raw PCM audio data
 * at 16kHz mono 16-bit (the format expected by Gemma 4 E4B).
 *
 * Uses Android's [AudioRecord] API directly â€” NOT MediaRecorder â€”
 * because the model needs raw PCM bytes, not compressed audio containers.
 */
@Singleton
class AudioInputProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        /** Sample rate required by Gemma 4 E4B native audio input. */
        const val SAMPLE_RATE = 16000
        /** Mono channel for single-microphone capture. */
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        /** PCM 16-bit encoding â€” each sample is a signed 16-bit integer. */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** Maximum recording duration in milliseconds (Gemma 4 E4B limit). */
        const val MAX_RECORDING_DURATION_MS = 30_000L
        /** Target amplitude emission rate â€” roughly 20 times per second. */
        private const val AMPLITUDE_EMIT_INTERVAL_MS = 50L
    }

    private val bufferSize: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AUDIO_FORMAT)

    private val _recordingState = MutableStateFlow<AudioRecordingState>(AudioRecordingState.Idle)
    val recordingState: StateFlow<AudioRecordingState> = _recordingState.asStateFlow()

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: Flow<Float> = _amplitudeFlow

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var lastAudioData: ByteArray? = null
    private var lastDurationMs: Long = 0L

    /** Cooperative stop flag â€” set to true by stopRecording() so the recording
     *  loop exits gracefully and saves the data, rather than cancelling the
     *  coroutine (which would discard collected audio in the CancellationException handler). */
    @Volatile private var isStopping = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingCompleted: CompletableDeferred<ByteArray> = CompletableDeferred()

    /** Whether the processor is currently recording. */
    val isRecording: Boolean
        get() = _recordingState.value is AudioRecordingState.Recording

    /** Audio effects for noise suppression and automatic gain control */
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    /**
     * Start recording audio from the device microphone.
     *
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     * @throws IllegalStateException if recording is already in progress or AudioRecord init fails
     */
    suspend fun startRecording() {
        // Prevent concurrent recording
        if (isRecording) {
            throw IllegalStateException("Recording is already in progress")
        }

        // Check permission
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _recordingState.value = AudioRecordingState.Error("RECORD_AUDIO permission not granted")
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        // Validate buffer size
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            _recordingState.value = AudioRecordingState.Error("AudioRecord buffer size error â€” device may not support 16kHz PCM recording")
            throw IllegalStateException("AudioRecord buffer size error")
        }

        // Create AudioRecord instance
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: IllegalArgumentException) {
            _recordingState.value = AudioRecordingState.Error("AudioRecord initialization failed: ${e.message}")
            throw IllegalStateException("AudioRecord initialization failed", e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _recordingState.value = AudioRecordingState.Error("AudioRecord failed to initialize â€” state: ${record.state}")
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        // Attach audio effects for better quality
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                noiseSuppressor?.enabled = true
            } catch (_: Exception) { }
        }
        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(record.audioSessionId)
                automaticGainControl?.enabled = true
            } catch (_: Exception) { }
        }

        audioRecord = record
        _amplitudeFlow.value = 0f
        _recordingState.value = AudioRecordingState.Recording(elapsedSeconds = 0f, amplitude = 0f)
        // Create a fresh CompletableDeferred for this recording session
        recordingCompleted = CompletableDeferred()
        // Reset cooperative stop flag for this new recording session
        isStopping = false

        recordingJob = scope.launch(Dispatchers.IO) {
            val outputStream = ByteArrayOutputStream()
            val readBuffer = ShortArray(bufferSize / 2) // 16-bit samples = 2 bytes each
            val byteBuffer = ByteArray(readBuffer.size * 2) // for converting shorts to bytes
            val startTime = System.currentTimeMillis()
            var lastAmplitudeEmitTime = startTime

            try {
                record.startRecording()

                while (isActive) {
                    val elapsedTime = System.currentTimeMillis() - startTime

                    // Auto-stop at max duration
                    if (elapsedTime >= MAX_RECORDING_DURATION_MS) {
                        break
                    }

                    val numSamples = record.read(readBuffer, 0, readBuffer.size)
                    if (numSamples <= 0) continue

                    // Convert short samples to bytes (little-endian PCM 16-bit)
                    for (i in 0 until numSamples) {
                        val sample = readBuffer[i]
                        byteBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                    }
                    outputStream.write(byteBuffer, 0, numSamples * 2)

                    // Calculate RMS amplitude for visualization
                    var sumSquares = 0.0
                    for (i in 0 until numSamples) {
                        sumSquares += readBuffer[i].toDouble() * readBuffer[i].toDouble()
                    }
                    val rms = kotlin.math.sqrt(sumSquares / numSamples)
                    // Normalize to 0f..1f range (16-bit max is 32768)
                    val normalizedAmplitude = (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()

                    // Emit amplitude at ~20Hz for smooth visualization
                    val now = System.currentTimeMillis()
                    if (now - lastAmplitudeEmitTime >= AMPLITUDE_EMIT_INTERVAL_MS) {
                        lastAmplitudeEmitTime = now
                        val elapsedSec = (now - startTime) / 1000f
                        _amplitudeFlow.value = normalizedAmplitude
                        _recordingState.value = AudioRecordingState.Recording(
                            elapsedSeconds = elapsedSec,
                            amplitude = normalizedAmplitude
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Recording was cancelled â€” don't save data
                _recordingState.value = AudioRecordingState.Idle
                _amplitudeFlow.value = 0f
                if (!recordingCompleted.isCompleted) {
                    recordingCompleted.complete(ByteArray(0))
                }
                return@launch
            } catch (e: Exception) {
                _recordingState.value = AudioRecordingState.Error("Recording error: ${e.message}")
                _amplitudeFlow.value = 0f
                if (!recordingCompleted.isCompleted) {
                    recordingCompleted.complete(ByteArray(0))
                }
                return@launch
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                    // AudioRecord may already be stopped or released
                }
                record.release()
                audioRecord = null
                // Release audio effects
                noiseSuppressor?.release()
                noiseSuppressor = null
                automaticGainControl?.release()
                automaticGainControl = null
            }

            // Successfully completed recording
            val audioData = outputStream.toByteArray()
            val totalDurationMs = System.currentTimeMillis() - startTime

            if (audioData.isEmpty()) {
                _recordingState.value = AudioRecordingState.Error("No audio data recorded")
                _amplitudeFlow.value = 0f
                recordingCompleted.complete(ByteArray(0))
                return@launch
            }

            lastAudioData = audioData
            lastDurationMs = totalDurationMs

            // Save to temporary file for potential replay
            saveToTempFile(audioData)

            android.util.Log.d("AudioInputProcessor", "Recording completed successfully: ${audioData.size} bytes, ${totalDurationMs}ms")

            _amplitudeFlow.value = 0f
            _recordingState.value = AudioRecordingState.Ready(
                audioData = audioData,
                durationMs = totalDurationMs
            )
            recordingCompleted.complete(audioData)
        }
    }

    /**
     * Stop the current recording and return the raw PCM audio bytes.
     *
     * @return the recorded PCM audio data as a ByteArray, or an empty ByteArray if nothing was recorded
     */
    suspend fun stopRecording(): ByteArray {
        android.util.Log.d("AudioInputProcessor", "stopRecording called. isRecording=$isRecording, lastAudioData size=${lastAudioData?.size}")

        // Case 1: Recording already completed naturally and data is ready
        if (!isRecording && lastAudioData != null) {
            android.util.Log.d("AudioInputProcessor", "Recording already complete, returning lastAudioData")
            return lastAudioData ?: ByteArray(0)
        }

        // Case 2: Not recording and no data
        if (!isRecording) {
            android.util.Log.d("AudioInputProcessor", "Not recording and no data available")
            return ByteArray(0)
        }

        // Case 3: Recording is active â€” signal it to stop
        // Cancel the recording coroutine â€” the finally block will handle cleanup
        android.util.Log.d("AudioInputProcessor", "Cancelling recording job...")
        recordingJob?.cancel()

        // Wait for the recording job to complete with a timeout
        // This ensures we get the final audio data
        val result = withTimeoutOrNull(2000L) {
            recordingCompleted.await()
        }

        android.util.Log.d("AudioInputProcessor", "Recording stopped. Result size=${result?.size}, lastAudioData size=${lastAudioData?.size}")

        recordingJob = null
        return result ?: lastAudioData ?: ByteArray(0)
    }

    /**
     * Returns the last recorded audio data, or null if not ready.
     */
    fun getAudioData(): ByteArray? = lastAudioData


    /**
     * Clean up all AudioRecord resources, reset state to Idle,
     * and delete temporary audio files.
     */
    fun release() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null

        // Release audio effects
        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null

        lastAudioData = null
        lastDurationMs = 0L
        _amplitudeFlow.value = 0f
        _recordingState.value = AudioRecordingState.Idle

        // Complete any pending deferred
        if (!recordingCompleted.isCompleted) {
            recordingCompleted.complete(ByteArray(0))
        }
        // Reset for next session
        recordingCompleted = CompletableDeferred()

        // Delete temporary audio files
        val tempDir = context.cacheDir
        tempDir.listFiles()?.filter { it.name.startsWith("audio_rec_") && it.name.endsWith(".pcm") }
            ?.forEach { it.delete() }
    }

    /**
     * Save the PCM audio data to a persistent file.
     *
     * @param filename the base filename (without extension)
     * @return the absolute path of the saved file, or null if no data is available
     */
    fun saveRecordingToFile(filename: String): String? {
        val data = lastAudioData ?: return null

        val audioDir = File(context.filesDir, "audio")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }

        val file = File(audioDir, "$filename.pcm")
        return try {
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save audio data to a temporary file in the cache directory.
     */
    private fun saveToTempFile(audioData: ByteArray) {
        try {
            val tempFile = File(context.cacheDir, "audio_rec_${System.currentTimeMillis()}.pcm")
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioData)
            }
        } catch (_: Exception) {
            // Non-critical â€” the data is still available in memory via lastAudioData
        }
    }
}