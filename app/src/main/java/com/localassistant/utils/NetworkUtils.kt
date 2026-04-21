package com.localassistant.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for network-related operations.
 */
@Singleton
class NetworkUtils @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Checks if the device has an active internet connection.
     */
    fun isInternetAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Checks if the device is connected to WiFi.
     */
    fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Checks if the device is connected to cellular data.
     */
    fun isCellularConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * Checks if large downloads should be allowed (WiFi only preference).
     */
    fun shouldAllowLargeDownload(allowOnCellular: Boolean = false): Boolean {
        if (!isInternetAvailable()) return false
        if (allowOnCellular) return true
        return isWifiConnected()
    }

    /**
     * Gets the estimated download speed category.
     */
    fun getNetworkSpeedCategory(): NetworkSpeed {
        val network = connectivityManager.activeNetwork ?: return NetworkSpeed.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkSpeed.NONE

        return when {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkSpeed.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkSpeed.FAST
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkSpeed.FAST
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // Check for 5G/4G/3G
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when (capabilities.signalStrength) {
                        in 4..Int.MAX_VALUE -> NetworkSpeed.FAST
                        in 3..3 -> NetworkSpeed.MEDIUM
                        else -> NetworkSpeed.SLOW
                    }
                } else {
                    NetworkSpeed.MEDIUM
                }
            }
            else -> NetworkSpeed.UNKNOWN
        }
    }

    /**
     * Estimates download time for a given file size.
     * @param fileSizeBytes The file size in bytes
     * @return Estimated time in seconds, or null if cannot estimate
     */
    fun estimateDownloadTime(fileSizeBytes: Long): Long? {
        // Assume different speeds based on network type
        val estimatedSpeedBps = when (getNetworkSpeedCategory()) {
            NetworkSpeed.FAST -> 10_000_000L // ~10 MB/s
            NetworkSpeed.MEDIUM -> 2_000_000L // ~2 MB/s
            NetworkSpeed.SLOW -> 500_000L // ~500 KB/s
            NetworkSpeed.UNKNOWN -> 1_000_000L // ~1 MB/s
            NetworkSpeed.NONE -> return null
        }
        return (fileSizeBytes / estimatedSpeedBps).coerceAtLeast(1L)
    }
}

enum class NetworkSpeed {
    NONE,
    SLOW,    // 2G/3G
    MEDIUM,  // 4G
    FAST,    // 5G/WiFi
    UNKNOWN
}
