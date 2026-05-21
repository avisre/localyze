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
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ModelEntry(
    val name: String,
    val displayName: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
    val sha256Hash: String = ""
)

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val MODEL_DIR = "models"
        private const val TAG = "ModelRepository"

        // Single-model build for the Play Store ship: Gemma 3n E2B is the only
        // shipped model. Head-to-head benchmarking on OnePlus 10 Pro showed
        // Gemma 3n E2B was 1.4-1.8x faster end-to-end and more correct on the
        // hard reasoning prompts where E4B digit-garbled. Legacy MODEL_E4B and
        // MODEL_E2B are retained as @Deprecated aliases so straggler callers
        // still compile without behavioural change.
        val MODEL_E2B_3N = ModelEntry(
            name = "gemma-3n-E2B",
            displayName = "Gemma 3n E2B",
            // On-disk filename kept as "gemma-4-E2B-it.litertlm" so existing
            // user installs that already cached the file under the legacy
            // name don't re-download. Downstream code only reads
            // ModelEntry.filename.
            filename = "gemma-4-E2B-it.litertlm",
            url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm?download=true",
            sizeBytes = 3_655_827_456L,
            description = "Gemma 3n E2B – the on-device model.",
            sha256Hash = "" // TODO: pin hash once Google publishes one
        )

        @Deprecated("Single-model build; alias retained for legacy callers.", ReplaceWith("MODEL_E2B_3N"))
        val MODEL_E4B = MODEL_E2B_3N
        @Deprecated("Single-model build; alias retained for legacy callers.", ReplaceWith("MODEL_E2B_3N"))
        val MODEL_E2B = MODEL_E2B_3N

        val ALL_MODELS = listOf(MODEL_E2B_3N)

        // Legacy constants for backward compatibility
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm?download=true"
        const val MODEL_SIZE_BYTES = 3_655_827_456L

        const val TEST_DOWNLOAD_URL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json"
        const val TEST_FILE_SIZE_BYTES = 700_000L

        /** @deprecated Use per-model sha256Hash in ModelEntry instead. */
        @Deprecated("Use per-model sha256Hash")
        const val SHA256_HASH = ""
        const val MIN_RAM_BYTES = 8L * 1024 * 1024 * 1024

        private const val TEMP_FILENAME = "model_download.tmp"
        private const val BUFFER_SIZE = 524_288
        private const val STORAGE_BUFFER_BYTES = 500L * 1024 * 1024
        private const val MIN_MODEL_FILE_COMPLETENESS = 0.95
        private const val MIN_LEGACY_MODEL_BYTES = 100L * 1024 * 1024

        const val DEMO_MODE = false
        var isTestModel: Boolean = false
            private set

        // Download resume preferences
        private const val PREFS_NAME = "model_download_prefs"
        private const val PREF_DOWNLOAD_URL = "download_url"
        private const val PREF_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val PREF_TOTAL_BYTES = "total_bytes"
        private const val PREF_SELECTED_MODEL = "selected_model"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR)

    private val tempFile: File
        get() = File(modelDir, TEMP_FILENAME)

    fun getSelectedModel(): ModelEntry {
        // Single-model build: always returns Gemma 3n E2B.
        return MODEL_E2B_3N
    }

    fun setSelectedModel(model: ModelEntry) {
        prefs.edit().putString(PREF_SELECTED_MODEL, model.name).apply()
    }

    fun getAllModels(): List<ModelEntry> = ALL_MODELS

    fun getModelEntry(filename: String): ModelEntry? {
        return ALL_MODELS.find { it.filename == filename }
    }

    fun getDownloadConfig(): Triple<String, String, Long> {
        val model = getSelectedModel()
        com.localyze.utils.AppLog.d(TAG, "Using model: ${model.displayName}")
        return Triple(model.filename, model.url, model.sizeBytes)
    }

    /**
     * Gets download config for a specific model.
     */
    fun getDownloadConfigFor(model: ModelEntry): Triple<String, String, Long> {
        com.localyze.utils.AppLog.d(TAG, "Getting download config for: ${model.displayName}")
        return Triple(model.filename, model.url, model.sizeBytes)
    }

    fun isModelDownloaded(): Boolean {
        if (DEMO_MODE) return true
        return findModelFile() != null
    }

    fun isModelDownloaded(model: ModelEntry): Boolean {
        val file = File(modelDir, model.filename)
        return isUsableModelFile(file, model)
    }

    fun findModelFile(): File? {
        val selectedModel = getSelectedModel()
        val file = File(modelDir, selectedModel.filename)
        if (isUsableModelFile(file, selectedModel)) {
            com.localyze.utils.AppLog.d(TAG, "Found model: ${file.name} (${file.length() / 1024 / 1024} MB)")
            return file
        }
        // Check all models as fallback
        for (model in ALL_MODELS) {
            val f = File(modelDir, model.filename)
            if (isUsableModelFile(f, model)) {
                com.localyze.utils.AppLog.d(TAG, "Found fallback model: ${f.name} (${f.length() / 1024 / 1024} MB)")
                return f
            }
        }
        // Legacy Qualcomm fallback
        val qualcommFile = File(modelDir, "gemma-4-E2B-it_qualcomm_qcs8275.litertlm")
        if (qualcommFile.exists() && qualcommFile.length() >= MIN_LEGACY_MODEL_BYTES) {
            com.localyze.utils.AppLog.d(TAG, "Found Qualcomm model: ${qualcommFile.name} (${qualcommFile.length() / 1024 / 1024} MB)")
            return qualcommFile
        }
        return null
    }

    private fun isUsableModelFile(file: File, model: ModelEntry): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0L) return false
        if (com.localyze.BuildConfig.USE_TEST_DOWNLOAD) return true

        val minimumExpectedBytes = (model.sizeBytes * MIN_MODEL_FILE_COMPLETENESS).toLong()
        val usable = length >= minimumExpectedBytes
        if (!usable) {
            android.util.Log.w(
                TAG,
                "Ignoring incomplete model ${file.name}: ${length / 1024 / 1024} MB " +
                    "of expected ${model.sizeBytes / 1024 / 1024} MB"
            )
        }
        return usable
    }

    fun isTestModelFile(): Boolean = isTestModel

    fun getModelFilePath(): String = findModelFile()?.absolutePath
        ?: File(modelDir, getSelectedModel().filename).absolutePath

    fun getModelFileSize(): Long = findModelFile()?.length() ?: 0L

    /**
     * Gets the name of the currently downloaded model file.
     */
    fun getDownloadedModelName(): String? = findModelFile()?.name

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
        val total = prefs.getLong(PREF_TOTAL_BYTES, getSelectedModel().sizeBytes)
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

    /**
     * Downloads the currently selected model.
     */
    fun downloadModel(resume: Boolean = true): Flow<DownloadProgress> = flow {
        val config = getDownloadConfig()
        val model = getSelectedModel()
        downloadModelImpl(config.first, config.second, config.third, resume, model.sha256Hash)
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a specific model.
     */
    fun downloadModel(model: ModelEntry, resume: Boolean = true): Flow<DownloadProgress> = flow {
        val config = getDownloadConfigFor(model)
        setSelectedModel(model)
        downloadModelImpl(config.first, config.second, config.third, resume, model.sha256Hash)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.downloadModelImpl(
        targetFilename: String,
        downloadUrl: String,
        totalSizeHint: Long,
        resume: Boolean,
        expectedSha256: String = ""
    ) {
        val useTestDownload = com.localyze.BuildConfig.USE_TEST_DOWNLOAD

        val actualUrl = if (useTestDownload) TEST_DOWNLOAD_URL else downloadUrl
        val actualSizeHint = if (useTestDownload) TEST_FILE_SIZE_BYTES else totalSizeHint

        if (DEMO_MODE) {
            val totalBytes = actualSizeHint
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
            return
        }

        modelDir.mkdirs()

        var startByte = 0L
        val canResumeExistingDownload = resume && canResumeDownload()
        if (canResumeExistingDownload) {
            val (downloaded, total) = getResumableDownloadProgress()
            if (downloaded < total) {
                startByte = downloaded
                com.localyze.utils.AppLog.d(TAG, "Resuming download from byte $startByte")
                emit(DownloadProgress.Resuming(startByte, total))
            }
        } else if (shouldDeleteStaleTempFile(resume, canResumeExistingDownload, tempFile.exists())) {
            tempFile.delete()
            clearDownloadProgress()
        }

        if (!hasEnoughStorageForDownload(actualSizeHint, startByte)) {
            emit(
                DownloadProgress.Error(
                    "Not enough storage space. Need ~${formatFileSize(actualSizeHint + STORAGE_BUFFER_BYTES - startByte)} free.",
                    false
                )
            )
            return
        }

        val requestBuilder = Request.Builder()
            .url(actualUrl)
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")

        if (startByte > 0) {
            requestBuilder.header("Range", "bytes=$startByte-")
        }

        val request = requestBuilder.build()

        try {
            com.localyze.utils.AppLog.d(TAG, "Starting download from: $actualUrl")
            val response = okHttpClient.newCall(request).execute()
            com.localyze.utils.AppLog.d(TAG, "Response code: ${response.code}, contentLength: ${response.body?.contentLength()}")
            if (!response.isSuccessful) {
                val retryable = response.code in 500..599 || response.code == 429
                emit(DownloadProgress.Error("Server returned ${response.code}: ${response.message}", retryable))
                response.close()
                return
            }

            val responseBody = response.body ?: run {
                emit(DownloadProgress.Error("Empty response body from server.", true))
                return
            }

            val contentLength = responseBody.contentLength()
            val totalBytes = if (contentLength > 0) {
                startByte + contentLength
            } else {
                actualSizeHint
            }

            val inputStream = responseBody.byteStream()

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

                    if (currentTime - lastProgressSaveTime >= 5000) {
                        saveDownloadProgress(actualUrl, bytesDownloaded, totalBytes)
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

                    if (!hasEnoughStorageForDownload(actualSizeHint, bytesDownloaded)) {
                        emit(DownloadProgress.Error("Not enough storage space.", true))
                        raf.close()
                        inputStream.close()
                        response.close()
                        tempFile.delete()
                        clearDownloadProgress()
                        return
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                raf.close()
                inputStream.close()
                response.close()
                saveDownloadProgress(actualUrl, bytesDownloaded, totalBytes)
                throw e
            }

            raf.close()
            inputStream.close()
            response.close()

            if (bytesDownloaded == 0L) {
                tempFile.delete()
                clearDownloadProgress()
                emit(DownloadProgress.Error("Downloaded file is empty.", true))
                return
            }

            if (expectedSha256.isNotBlank()) {
                emit(DownloadProgress.Verifying(percent = 0f))
                val verified = verifySha256(tempFile, expectedSha256) {}
                if (!verified) {
                    tempFile.delete()
                    clearDownloadProgress()
                    emit(DownloadProgress.Error("File integrity check failed. The downloaded model does not match the expected checksum.", true))
                    return
                }
            }

            val destinationFile = File(modelDir, targetFilename)
            if (destinationFile.exists()) destinationFile.delete()
            if (!tempFile.renameTo(destinationFile)) {
                try {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                } catch (e: Exception) {
                    emit(DownloadProgress.Error("Failed to finalize model file: ${e.message}", true))
                    return
                }
            }

            clearDownloadProgress()

            com.localyze.utils.AppLog.d(
                TAG,
                "Model downloaded: ${destinationFile.name} (${destinationFile.length() / 1024 / 1024} MB)"
            )
            emit(DownloadProgress.Complete)
            if (useTestDownload) isTestModel = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            savePartialDownloadProgress(actualUrl, actualSizeHint)
            android.util.Log.e(TAG, "Download socket timeout", e)
            emit(DownloadProgress.Error("Connection timed out. Download will resume when possible.", true))
        } catch (e: java.net.UnknownHostException) {
            savePartialDownloadProgress(actualUrl, actualSizeHint)
            android.util.Log.e(TAG, "Download unknown host", e)
            emit(DownloadProgress.Error("Could not connect to server. Download will resume when connection is restored.", true))
        } catch (e: Exception) {
            savePartialDownloadProgress(actualUrl, actualSizeHint)
            android.util.Log.e(TAG, "Download error", e)
            emit(DownloadProgress.Error("Download failed: ${e.message ?: "Unknown error"}. Progress saved for resume.", true))
        }
    }

    private fun savePartialDownloadProgress(downloadUrl: String, totalSizeHint: Long) {
        val bytesDownloaded = tempFile.length()
        if (bytesDownloaded > 0L && bytesDownloaded < totalSizeHint) {
            saveDownloadProgress(downloadUrl, bytesDownloaded, totalSizeHint)
        }
    }

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

    fun hasEnoughStorageFor(model: ModelEntry): Boolean {
        return context.filesDir.usableSpace > (model.sizeBytes + STORAGE_BUFFER_BYTES)
    }

    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isCellularConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun shouldAllowDownload(allowOnCellular: Boolean): Boolean {
        if (isWifiConnected()) return true
        if (allowOnCellular && isCellularConnected()) return true
        return false
    }

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

    private fun hasEnoughStorageForDownload(modelSize: Long, bytesSoFar: Long): Boolean {
        return context.filesDir.usableSpace > (modelSize - bytesSoFar + STORAGE_BUFFER_BYTES)
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
