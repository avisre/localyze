package com.localassistant.ai

sealed class AudioRecordingState {
    data object Idle : AudioRecordingState()
    data class Recording(val elapsedSeconds: Float = 0f, val amplitude: Float = 0f) : AudioRecordingState()
    data class Ready(val audioData: ByteArray, val durationMs: Long) : AudioRecordingState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ready) return false
            return durationMs == other.durationMs && audioData.contentEquals(other.audioData)
        }
        override fun hashCode(): Int = 31 * durationMs.hashCode() + audioData.contentHashCode()
    }
    data class Error(val message: String) : AudioRecordingState()
}