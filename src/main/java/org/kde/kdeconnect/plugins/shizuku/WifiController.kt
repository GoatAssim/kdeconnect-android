/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class WifiController(private val context: Context) {

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun getStatus(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager
            if (wm == null) {
                result.put("error", "WifiManager unavailable")
                return result
            }

            result.put("enabled", wm.isWifiEnabled)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                result.put("connected", isWifi)

                if (isWifi) {
                    val link = cm.getLinkProperties(network)
                    result.put("interface", link?.interfaceName ?: "")
                }
            } else {
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                result.put("connected", info != null && info.networkId != -1)
                if (info != null) {
                    result.put("ssid", info.ssid?.trim('"') ?: "")
                    result.put("bssid", info.bssid ?: "")
                    result.put("rssi", info.rssi)
                    result.put("linkSpeedMbps", info.linkSpeed)
                    result.put("frequencyMhz", info.frequency)
                    result.put("ipAddress", intToIp(info.ipAddress))
                }
            }

            try {
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                if (info != null) {
                    result.put("rssi", info.rssi)
                    result.put("signalLevel", WifiManager.calculateSignalLevel(info.rssi, 5))
                }
            } catch (_: Throwable) {
            }

        } catch (e: Throwable) {
            result.put("error", e.message ?: "WiFi status error")
        }
        return result
    }

    fun scanNetworks(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager
            if (wm == null) {
                result.put("error", "WifiManager unavailable")
                return result
            }

            @Suppress("DEPRECATION")
            val success = wm.startScan()
            result.put("scanStarted", success)

            @Suppress("DEPRECATION")
            val results = wm.scanResults
            val arr = JSONArray()
            results?.forEach { sr ->
                val obj = JSONObject()
                obj.put("ssid", sr.SSID ?: "")
                obj.put("bssid", sr.BSSID ?: "")
                obj.put("level", sr.level)
                obj.put("frequency", sr.frequency)
                obj.put("capabilities", sr.capabilities ?: "")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    obj.put("channelWidth", sr.channelWidth)
                }
                arr.put(obj)
            }
            result.put("networks", arr)
            result.put("count", arr.length())
        } catch (e: Throwable) {
            result.put("error", e.message ?: "WiFi scan error")
        }
        return result
    }

    fun setEnabled(enabled: Boolean): JSONObject {
        val result = JSONObject()
        try {
            // Prefer Shizuku shell – app-process setWifiEnabled returns false on modern Android
            val state = if (enabled) "enabled" else "disabled"
            val svcArg = if (enabled) "enable" else "disable"

            val r = ShizukuHelper.runShellFirstSuccess(
                arrayOf("cmd", "wifi", "set-wifi-enabled", state),
                arrayOf("svc", "wifi", svcArg),
            )

            if (r == null) {
                // Fallback (usually fails without system privilege)
                val wm = wifiManager
                if (wm == null) {
                    result.put("success", false)
                    result.put("error", "Shizuku not available and WifiManager unavailable")
                    return result
                }
                @Suppress("DEPRECATION")
                val ok = wm.setWifiEnabled(enabled)
                result.put("success", ok)
                result.put("enabled", enabled)
                result.put("method", "WifiManager.setWifiEnabled")
                if (!ok) {
                    result.put(
                        "error",
                        "setWifiEnabled returned false (need Shizuku running + permission granted to KDE Connect)"
                    )
                }
                return result
            }

            val (exit, stdout, stderr) = r
            result.put("success", exit == 0)
            result.put("exitCode", exit)
            result.put("stdout", stdout)
            result.put("stderr", stderr)
            result.put("enabled", enabled)
            result.put("method", "shizuku-shell")
            if (exit != 0) {
                result.put("error", stderr.ifBlank { "wifi toggle failed with code $exit" })
            }
        } catch (e: Throwable) {
            result.put("success", false)
            result.put("error", e.message ?: "Failed to toggle WiFi")
        }
        return result
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}