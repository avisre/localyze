package com.localyze.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val MODEL_DIR = "models"
        private const val TAG = "ModelRepository"

        // The app intentionally supports one model: Gemma 4 E4B.
        const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true"
        const val MODEL_SIZE_BYTES = 3_654_467_584L

        const val TEST_DOWNLOAD_URL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json"
        const val TEST_FILE_SIZE_BYTES = 700_000L

        const val SHA256_HASH = ""
        const val MIN_RAM_BYTES = 8L * 1024 * 1024 * 1024

        private const val TEMP_FILENAME = "model_download.tmp"
        private const val BUFFER_SIZE = 524_288
        private const val STORAGE_BUFFER_BYTES = 500L * 1024 * 1024

        const val DEMO_MODE = false
        var isTestModel: Boolean = false
            private set

        // Download resume preferences
        private const val PREFS_NAME = "model_download_prefs"
        private const val PREF_DOWNLOAD_URL = "download_url"
        private const val PREF_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val PREF_TOTAL_BYTES = "total_bytes"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR)

    private val tempFile: File
        get() = File(modelDir, TEMP_FILENAME)

    fun getDownloadConfig(): Triple<String, String, Long> {
        android.util.Log.d(TAG, "Using Gemma 4 E4B only")
        return Triple(MODEL_FILENAME, MODEL_URL, MODEL_SIZE_BYTES)
    }

    fun isModelDownloaded(): Boolean {
        if (DEMO_MODE) return true
        return findModelFile() != null
    }

    fun findModelFile(): File? {
        val file = File(modelDir, MODEL_FILENAME)
        if (file.exists() && file.length() > 0L) {
            android.util.Log.d(TAG, "Found model: ${file.name} (${file.length() / 1024 / 1024} MB)")
            return file
        }
        return null
    }

    fun isTestModelFile(): Boolean = isTestModel

    fun getModelFilePath(): String = findModelFile()?.absolutePath
        ?: File(modelDir, MODEL_FILENAME).absolutePath

    fun getModelFileSize(): Long = findModelFile()?.length() ?: 0L

    /**
     * Checks if there's an incomplete download that can be resumed.
     */
    fun canResumeDownload(): Boolean {
        if (!tempFile.exists()) return false
        val savedUrl = prefs.getString(PREF_DOWNLOAD_URL, null) ?: return false
        val currentUrl = getDownloadConfig().second
        return savedUrl == currentUrl && tempFile.length() > 0
    }

    /**
     * Gets the number of bytes already downloaded for resume.
     */
    fun getResumableDownloadProgress(): Pair<Long, Long> {
        val downloaded = tempFile.length()
        val total = prefs.getLong(PREF_TOTAL_BYTES, MODEL_SIZE_BYTES)
        return Pair(downloaded, total)
    }

    /**
     * Clears any incomplete download state.
     */
    fun clearIncompleteDownload() {
        tempFile.delete()
        prefs.edit().apply {
            remove(PREF_DOWNLOAD_URL)
            remove(PREF_DOWNLOADED_BYTES)
            remove(PREF_TOTAL_BYTES)
        }.apply()
    }

    private fun saveDownloadProgress(url: String, downloaded: Long, total: Long) {
        prefs.edit().apply {
            putString(PREF_DOWNLOAD_URL, url)
            putLong(PREF_DOWNLOADED_BYTES, downloaded)
            putLong(PREF_TOTAL_BYTES, total)
        }.apply()
    }

    private fun clearDownloadProgress() {
        prefs.edit().apply {
            remove(PREF_DOWNLOAD_URL)
            remove(PREF_DOWNLOADED_BYTES)
            remove(PREF_TOTAL_BYTES)
        }.apply()
    }

    fun downloadModel(resume: Boolean = true): Flow<DownloadProgress> = flow {
        val useTestDownload = com.localyze.BuildConfig.USE_TEST_DOWNLOAD

        val (targetFilename, downloadUrl, totalSizeHint) = if (useTestDownload) {
            Triple(MODEL_FILENAME, TEST_DOWNLOAD_URL, TEST_FILE_SIZE_BYTES)
        } else {
            getDownloadConfig()
        }

        if (DEMO_MODE) {
            val totalBytes = totalSizeHint
            val steps = 20
            val delayPerStep = 100L
            for (i in 1..steps) {
                val bytesDownloaded = (totalBytes * i) / steps
                val percent = i / steps.toFloat()
                emit(
                    DownloadProgress.Downloading(
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        percent = percent,
                        estimatedSecondsRemaining = ((steps - i) * delayPerStep) / 1000
                    )
                )
                kotlinx.coroutines.delay(delayPerStep)
            }
            emit(DownloadProgress.Verifying(percent = 0.5f))
            kotlinx.coroutines.delay(200)
            emit(DownloadProgress.Verifying(percent = 1f))
            kotlinx.coroutines.delay(200)
            emit(DownloadProgress.Complete)
            return@flow
        }

        modelDir.mkdirs()

        // Check for resumable download
        var startByte = 0L
        if (resume && canResumeDownload()) {
            val (downloaded, total) = getResumableDownloadProgress()
            if (downloaded < total) {
                startByte = downloaded
                android.util.Log.d(TAG, "Resuming download from byte $startByte")
                emit(DownloadProgress.Resuming(startByte, total))
            }
        } else if (!resume && tempFile.exists()) {
            tempFile.delete()
            clearDownloadProgress()
        }

        if (!hasEnoughStorageForDownload(startByte)) {
            emit(
                DownloadProgress.Error(
                    "Not enough storage space. Need ~${formatFileSize(totalSizeHint + STORAGE_BUFFER_BYTES - startByte)} free.",
                    false
                )
            )
            return@flow
        }

        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")

        // Add Range header if resuming
        if (startByte > 0) {
            requestBuilder.header("Range", "bytes=$startByte-")
        }

        val request = requestBuilder.build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val retryable = response.code in 500..599 || response.code == 429
                emit(DownloadProgress.Error("Server returned ${response.code}: ${response.message}", retryable))
                response.close()
                return@flow
            }

            val responseBody = response.body ?: run {
                emit(DownloadProgress.Error("Empty response body from server.", true))
                return@flow
            }

            val contentLength = responseBody.contentLength()
            // For resumed downloads, add the existing file size to get total
            val totalBytes = if (contentLength > 0) {
                startByte + contentLength
            } else {
                totalSizeHint
            }

            val inputStream = responseBody.byteStream()

            // Use RandomAccessFile for resume support
            val raf = RandomAccessFile(tempFile, "rw")
            if (startByte > 0) {
                raf.seek(startByte)
            }

            var bytesDownloaded = startByte
            var lastSpeedCalculationTime = System.currentTimeMillis()
            var lastSpeedCalculationBytes = startByte
            var estimatedSecondsRemaining = 0L
            var lastEmitTime = System.currentTimeMillis()
            var lastProgressSaveTime = System.currentTimeMillis()
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    raf.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastSpeedCalc = currentTime - lastSpeedCalculationTime

                    if (timeSinceLastSpeedCalc >= 500) {
                        val bytesSinceLastCalc = bytesDownloaded - lastSpeedCalculationBytes
                        val speedBytesPerSec = if (timeSinceLastSpeedCalc > 0) {
                            (bytesSinceLastCalc * 1000L) / timeSinceLastSpeedCalc
                        } else {
                            0L
                        }
                        estimatedSecondsRemaining = if (speedBytesPerSec > 0) {
                            (totalBytes - bytesDownloaded) / speedBytesPerSec
                        } else {
                            0L
                        }
                        lastSpeedCalculationTime = currentTime
                        lastSpeedCalculationBytes = bytesDownloaded
                    }

                    // Save progress periodically (every 5 seconds)
                    if (currentTime - lastProgressSaveTime >= 5000) {
                        saveDownloadProgress(downloadUrl, bytesDownloaded, totalBytes)
                        lastProgressSaveTime = currentTime
                    }

                    if (currentTime - lastEmitTime >= 500 || bytesRead < BUFFER_SIZE) {
                        val percent = if (totalBytes > 0) {
                            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        emit(
                            DownloadProgress.Downloading(
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                percent = percent,
                                estimatedSecondsRemaining = estimatedSecondsRemaining
                            )
                        )
                        lastEmitTime = currentTime
                    }

                    if (!hasEnoughStorageForDownload(bytesDownloaded)) {
                        emit(DownloadProgress.Error("Not enough storage space.", true))
                        raf.close()
                        inputStream.close()
                        response.close()
                        tempFile.delete()
                        clearDownloadProgress()
                        return@flow
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                raf.close()
                inputStream.close()
                response.close()
                // Save progress for resume instead of deleting
                saveDownloadProgress(downloadUrl, bytesDownloaded, totalBytes)
                throw e
            }

            raf.close()
            inputStream.close()
            response.close()

            if (bytesDownloaded == 0L) {
                tempFile.delete()
                clearDownloadProgress()
                emit(DownloadProgress.Error("Downloaded file is empty.", true))
                return@flow
            }

            if (SHA256_HASH.isNotEmpty()) {
                emit(DownloadProgress.Verifying(percent = 0f))
                val verified = verifySha256(tempFile, SHA256_HASH) {}
                if (!verified) {
                    tempFile.delete()
                    clearDownloadProgress()
                    emit(DownloadProgress.Error("File integrity check failed.", true))
                    return@flow
                }
            } else {
                emit(DownloadProgress.Verifying(percent = 0.5f))
                kotlinx.coroutines.delay(100)
                emit(DownloadProgress.Verifying(percent = 1f))
                kotlinx.coroutines.delay(100)
            }

            val destinationFile = File(modelDir, targetFilename)
            if (destinationFile.exists()) destinationFile.delete()
            if (!tempFile.renameTo(destinationFile)) {
                try {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                } catch (e: Exception) {
                    emit(DownloadProgress.Error("Failed to finalize model file: ${e.message}", true))
                    return@flow
                }
            }

            // Clear download progress on success
            clearDownloadProgress()

            android.util.Log.d(
                TAG,
                "Model downloaded: ${destinationFile.name} (${destinationFile.length() / 1024 / 1024} MB)"
            )
            emit(DownloadProgress.Complete)
            if (useTestDownload) isTestModel = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Progress already saved in the inner catch block
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            // Keep temp file for resume
            emit(DownloadProgress.Error("Connection timed out. Download will resume when possible.", true))
        } catch (e: java.net.UnknownHostException) {
            // Keep temp file for resume
            emit(DownloadProgress.Error("Could not connect to server. Download will resume when connection is restored.", true))
        } catch (e: Exception) {
            // For other errors, keep temp file for potential resume
            emit(DownloadProgress.Error("Download failed: ${e.message ?: "Unknown error"}. Progress saved for resume.", true))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun verifySha256(
        file: File,
        expectedHash: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val fileSize = file.length()
        var bytesProcessed = 0L
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesProcessed += bytesRead
                onProgress(bytesProcessed.toFloat() / fileSize.toFloat())
            }
        }
        return digest.digest()
            .joinToString("") { "%02x".format(it) }
            .equals(expectedHash, ignoreCase = true)
    }

    fun deleteModel(): Boolean {
        return modelDir.listFiles()?.all { it.delete() } ?: true
    }

    fun hasMinimumRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem >= MIN_RAM_BYTES
    }

    fun getAvailableStorage(): Long = context.filesDir.usableSpace

    fun hasEnoughStorage(): Boolean {
        return context.filesDir.usableSpace > (MODEL_SIZE_BYTES + STORAGE_BUFFER_BYTES)
    }

    /**
     * Checks if the device is connected to WiFi.
     */
    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Checks if the device is connected to cellular data.
     */
    fun isCellularConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * Checks if large downloads should be allowed based on network type.
     */
    fun shouldAllowDownload(allowOnCellular: Boolean): Boolean {
        if (isWifiConnected()) return true
        if (allowOnCellular && isCellularConnected()) return true
        return false
    }

    /**
     * Gets a human-readable description of the current network state.
     */
    fun getNetworkStatus(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Unknown"
        val network = connectivityManager.activeNetwork ?: return "No Connection"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "No Connection"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Unknown"
        }
    }

    private fun hasEnoughStorageForDownload(bytesSoFar: Long): Boolean {
        return context.filesDir.usableSpace > (MODEL_SIZE_BYTES - bytesSoFar + STORAGE_BUFFER_BYTES)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }
}
