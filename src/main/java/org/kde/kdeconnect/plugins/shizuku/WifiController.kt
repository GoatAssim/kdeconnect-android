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
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "WiFi status error")
        }
        return result
    }

    fun scanNetworks(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }
            @Suppress("DEPRECATION")
            result.put("scanStarted", wm.startScan())
            @Suppress("DEPRECATION")
            val results = wm.scanResults
            val arr = JSONArray()
            results?.forEach { sr ->
                val obj = JSONObject()
                obj.put("ssid", sr.SSID ?: "")
                obj.put("bssid", sr.BSSID ?: "")
                obj.put("level", sr.level)
                obj.put("frequency", sr.frequency)
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
        result.put("shizukuAvailable", ShizukuHelper.isAvailable())
        result.put("shizukuPermission", ShizukuHelper.isPermissionGranted())
        result.put("shizukuUid", ShizukuHelper.getUid())

        val notReady = ShizukuHelper.notReadyReason()
        if (notReady != null) {
            result.put("success", false)
            result.put("error", notReady)
            result.put("method", "none")
            return result
        }

        val state = if (enabled) "enabled" else "disabled"
        val svcArg = if (enabled) "enable" else "disable"

        val r = ShizukuHelper.runShellFirstSuccess(
            arrayOf("cmd", "wifi", "set-wifi-enabled", state),
            arrayOf("svc", "wifi", svcArg),
        )

        if (r == null) {
            result.put("success", false)
            result.put("error", ShizukuHelper.notReadyReason() ?: "runShell returned null")
            result.put("method", "none")
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
            result.put("error", stderr.ifBlank { "wifi toggle failed code $exit" })
        }
        return result
    }
}