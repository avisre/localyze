package com.localassistant.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class SystemInfoTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "system_info"
    override val description = "Get device system information including battery level, WiFi status, and available storage"

    // ── Schema ─────────────────────────────────────────────────────────────

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("info_type", buildJsonObject {
                put("type", "string")
                put("description", "Type of info to retrieve: 'all', 'battery', 'wifi', or 'storage' (default 'all')")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("all")); add(JsonPrimitive("battery"))
                    add(JsonPrimitive("wifi")); add(JsonPrimitive("storage"))
                })
            })
        })
        put("required", buildJsonArray { })
    }

    // ── Execute ────────────────────────────────────────────────────────────

    override suspend fun execute(args: JsonObject): String {
        val infoType = args["info_type"]?.let { (it as JsonPrimitive).content } ?: "all"

        return when (infoType) {
            "battery" -> getBatteryInfo().toString()
            "wifi" -> getWifiInfo().toString()
            "storage" -> getStorageInfo().toString()
            "all" -> buildJsonObject {
                put("battery", getBatteryInfo())
                put("wifi", getWifiInfo())
                put("storage", getStorageInfo())
                put("device", getDeviceInfo())
            }.toString()
            else -> errorResult("Unknown info_type: $infoType. Use 'all', 'battery', 'wifi', or 'storage'.")
        }
    }

    // ── Battery ────────────────────────────────────────────────────────────

    private fun getBatteryInfo(): JsonObject {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            // Check charging status via sticky broadcast
            val chargingStatus = try {
                val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) { false }

            buildJsonObject {
                put("level", level)
                put("is_charging", chargingStatus)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("level", -1)
                put("is_charging", false)
                put("error", "Failed to read battery info: ${e.message}")
            }
        }
    }

    // ── WiFi ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getWifiInfo(): JsonObject {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

            val isConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            // Try to get SSID (requires ACCESS_FINE_LOCATION on API 27+)
            val ssid = try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wifiManager?.connectionInfo
                val ssidValue = info?.ssid
                // Remove quotes and check for unknown SSID
                if (ssidValue != null && ssidValue != "<unknown ssid>") {
                    ssidValue.removeSurrounding("\"")
                } else {
                    null
                }
            } catch (_: Exception) { null }

            val linkDownstreamMbps = capabilities?.linkDownstreamBandwidthKbps?.let { it / 1000 } ?: -1

            buildJsonObject {
                put("is_connected", isConnected)
                put("is_wifi", isWifi)
                put("ssid", ssid ?: "unknown")
                put("estimated_download_mbps", linkDownstreamMbps)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("is_connected", false)
                put("is_wifi", false)
                put("ssid", "unknown")
                put("error", "Failed to read WiFi info: ${e.message}")
            }
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun getStorageInfo(): JsonObject {
        return try {
            val dataDir = android.os.Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)

            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes
            val usedPercent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

            buildJsonObject {
                put("total_gb", String.format("%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0)))
                put("available_gb", String.format("%.1f", availableBytes / (1024.0 * 1024.0 * 1024.0)))
                put("used_percent", usedPercent)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("total_gb", "0")
                put("available_gb", "0")
                put("used_percent", 0)
                put("error", "Failed to read storage info: ${e.message}")
            }
        }
    }

    // ── Device info ────────────────────────────────────────────────────────

    private fun getDeviceInfo(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("android_version", Build.VERSION.RELEASE)
        put("sdk_version", Build.VERSION.SDK_INT)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}