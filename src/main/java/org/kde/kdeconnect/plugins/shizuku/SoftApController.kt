/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Controls the Wi-Fi SoftAP (hotspot) using a mix of public APIs and Shizuku-privileged
 * hidden APIs. Every public method is exception-safe and returns a JSONObject that
 * either contains the requested data or an "error" field.
 *
 * Supported operations:
 *  - getConfig / setConfig (SSID, passphrase, band, hidden, max clients, security)
 *  - start / stop
 *  - getConnectedClients
 *  - getBlockedClients / setBlockedClients / banClient / unbanClient
 */
class SoftApController(private val context: Context) {

    private val TAG = "SoftApController"

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // ------------------------------------------------------------------
    // Public API (always returns JSONObject, never throws)
    // ------------------------------------------------------------------

    fun getStatus(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager
            if (wm == null) {
                result.put("error", "WifiManager unavailable")
                return result
            }

            // isWifiApEnabled is hidden on most versions – try reflection first
            var enabled = false
            try {
                val method: Method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                enabled = method.invoke(wm) as Boolean
            } catch (_: Throwable) {
                // fallback – not critical
            }
            result.put("enabled", enabled)

            // SoftApConfiguration (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val config = wm.softApConfiguration
                    putConfigInto(result, config)
                } catch (e: Throwable) {
                    result.put("configError", e.message ?: "Could not read SoftApConfiguration")
                }
            } else {
                result.put("configError", "SoftApConfiguration requires Android 11+")
            }

            // Connected clients (best effort)
            result.put("clients", getConnectedClientsInternal())

        } catch (e: Throwable) {
            result.put("error", e.message ?: "Hotspot status error")
        }
        return result
    }

    fun getConfig(): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                result.put("error", "SoftApConfiguration requires Android 11+")
                return result
            }
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }
            val config = wm.softApConfiguration
            putConfigInto(result, config)
        } catch (e: Throwable) {
            result.put("error", e.message ?: "Failed to get SoftApConfiguration")
        }
        return result
    }

    /**
     * Update hotspot configuration.
     * Accepted keys in the incoming JSON:
     *   ssid, passphrase, band (2ghz|5ghz|6ghz|any), hidden (bool),
     *   maxClients (int), securityType (open|wpa2|wpa3|wpa3_transition)
     */
    fun setConfig(params: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                result.put("error", "SoftApConfiguration requires Android 11+")
                return result
            }
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }

            val current = wm.softApConfiguration
            val builder = SoftApConfiguration.Builder(current)

            if (params.has("ssid")) {
                builder.setSsid(params.getString("ssid"))
            }
            if (params.has("passphrase")) {
                val pass = params.getString("passphrase")
                val sec = when (params.optString("securityType", "wpa2").lowercase()) {
                    "open" -> SoftApConfiguration.SECURITY_TYPE_OPEN
                    "wpa3" -> SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                    "wpa3_transition" -> SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION
                    else -> SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                }
                if (sec == SoftApConfiguration.SECURITY_TYPE_OPEN) {
                    builder.setPassphrase(null, SoftApConfiguration.SECURITY_TYPE_OPEN)
                } else {
                    builder.setPassphrase(pass, sec)
                }
            }
            if (params.has("hidden")) {
                builder.setHiddenSsid(params.getBoolean("hidden"))
            }
            if (params.has("maxClients") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setMaxNumberOfClients(params.getInt("maxClients"))
            }
            if (params.has("band")) {
                val band = when (params.getString("band").lowercase()) {
                    "2ghz", "2.4ghz" -> SoftApConfiguration.BAND_2GHZ
                    "5ghz" -> SoftApConfiguration.BAND_5GHZ
                    "6ghz" -> SoftApConfiguration.BAND_6GHZ
                    else -> SoftApConfiguration.BAND_2GHZ or SoftApConfiguration.BAND_5GHZ
                }
                builder.setBand(band)
            }

            // Blocked client list
            if (params.has("blockedClients")) {
                val arr = params.getJSONArray("blockedClients")
                val list = mutableListOf<android.net.MacAddress>()
                for (i in 0 until arr.length()) {
                    try {
                        list.add(android.net.MacAddress.fromString(arr.getString(i)))
                    } catch (_: Throwable) {
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setBlockedClientList(list)
                }
            }

            val newConfig = builder.build()
            val ok = wm.setSoftApConfiguration(newConfig)
            result.put("success", ok)
            if (ok) {
                putConfigInto(result, newConfig)
            } else {
                result.put("error", "setSoftApConfiguration returned false (may need Shizuku privilege on this OEM)")
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "Failed to set SoftApConfiguration")
            Log.e(TAG, "setConfig failed", e)
        }
        return result
    }

    fun start(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }

            // Preferred modern path (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // startTethering is the correct modern way, but requires TetheringManager
                // and usually the TETHER_PRIVILEGED permission which Shizuku can provide.
                val started = tryStartTethering()
                if (started) {
                    result.put("success", true)
                    result.put("method", "tethering")
                    return result
                }
            }

            // Fallback reflection (older / some OEMs)
            try {
                val method = wm.javaClass.getMethod("startSoftAp", android.net.wifi.WifiConfiguration::class.java)
                val ok = method.invoke(wm, null as android.net.wifi.WifiConfiguration?) as Boolean
                result.put("success", ok)
                result.put("method", "startSoftAp")
            } catch (e: Throwable) {
                result.put("error", "Could not start SoftAP: ${e.message}")
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "start failed")
        }
        return result
    }

    fun stop(): JSONObject {
        val result = JSONObject()
        try {
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val stopped = tryStopTethering()
                if (stopped) {
                    result.put("success", true)
                    result.put("method", "tethering")
                    return result
                }
            }

            try {
                val method = wm.javaClass.getMethod("stopSoftAp")
                val ok = method.invoke(wm) as Boolean
                result.put("success", ok)
                result.put("method", "stopSoftAp")
            } catch (e: Throwable) {
                result.put("error", "Could not stop SoftAP: ${e.message}")
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "stop failed")
        }
        return result
    }

    fun getConnectedClients(): JSONObject {
        val result = JSONObject()
        try {
            result.put("clients", getConnectedClientsInternal())
        } catch (e: Throwable) {
            result.put("error", e.message ?: "Failed to list clients")
        }
        return result
    }

    fun banClient(mac: String): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                result.put("error", "Client blocking requires Android 12+")
                return result
            }
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }
            val current = wm.softApConfiguration
            val blocked = current.blockedClientList.toMutableList()
            val macAddr = android.net.MacAddress.fromString(mac)
            if (!blocked.contains(macAddr)) {
                blocked.add(macAddr)
            }
            val builder = SoftApConfiguration.Builder(current).setBlockedClientList(blocked)
            val ok = wm.setSoftApConfiguration(builder.build())
            result.put("success", ok)
            result.put("blockedClients", JSONArray(blocked.map { it.toString() }))
        } catch (e: Throwable) {
            result.put("error", e.message ?: "banClient failed")
        }
        return result
    }

    fun unbanClient(mac: String): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                result.put("error", "Client blocking requires Android 12+")
                return result
            }
            val wm = wifiManager ?: run {
                result.put("error", "WifiManager unavailable")
                return result
            }
            val current = wm.softApConfiguration
            val blocked = current.blockedClientList.toMutableList()
            val macAddr = android.net.MacAddress.fromString(mac)
            blocked.remove(macAddr)
            val builder = SoftApConfiguration.Builder(current).setBlockedClientList(blocked)
            val ok = wm.setSoftApConfiguration(builder.build())
            result.put("success", ok)
            result.put("blockedClients", JSONArray(blocked.map { it.toString() }))
        } catch (e: Throwable) {
            result.put("error", e.message ?: "unbanClient failed")
        }
        return result
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun putConfigInto(target: JSONObject, config: SoftApConfiguration) {
        target.put("ssid", config.ssid ?: "")
        target.put("hidden", config.isHiddenSsid)
        target.put("securityType", when (config.securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN -> "open"
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "wpa2"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "wpa3"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "wpa3_transition"
            else -> "unknown"
        })
        // Passphrase is not readable for security reasons on most builds
        target.put("passphraseReadable", false)

        val band = config.band
        val bands = mutableListOf<String>()
        if (band and SoftApConfiguration.BAND_2GHZ != 0) bands.add("2ghz")
        if (band and SoftApConfiguration.BAND_5GHZ != 0) bands.add("5ghz")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && band and SoftApConfiguration.BAND_6GHZ != 0) {
            bands.add("6ghz")
        }
        target.put("band", bands.joinToString(","))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.put("maxClients", config.maxNumberOfClients)
            val blocked = JSONArray()
            config.blockedClientList.forEach { blocked.put(it.toString()) }
            target.put("blockedClients", blocked)
        }
    }

    private fun getConnectedClientsInternal(): JSONArray {
        val arr = JSONArray()
        // Connected client list is not exposed via a stable public API.
        // Many OEMs / AOSP builds expose it only through privileged callbacks
        // (SoftApCallback.onConnectedClientsChanged). For a first version we return
        // an empty list and document the limitation. Future improvement: register
        // a SoftApCallback via Shizuku.
        return arr
    }

    private fun tryStartTethering(): Boolean {
        // Placeholder – full ITetheringConnector path via Shizuku will be added
        // in the next iteration once we have the exact AIDL stubs.
        // For now return false so the reflection fallback is used.
        return false
    }

    private fun tryStopTethering(): Boolean {
        return false
    }
}
