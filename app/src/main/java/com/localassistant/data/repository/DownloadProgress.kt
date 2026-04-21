package com.localassistant.data.repository

sealed class DownloadProgress {
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Float,
        val estimatedSecondsRemaining: Long
    ) : DownloadProgress()

    data class Resuming(
        val bytesAlreadyDownloaded: Long,
        val totalBytes: Long
    ) : DownloadProgress()

    data class Verifying(val percent: Float) : DownloadProgress()

    data object Complete : DownloadProgress()

    data class Error(val message: String, val isRetryable: Boolean) : DownloadProgress()
}