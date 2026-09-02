/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * SoftAP / hotspot control.
 * Mutations go through Shizuku shell first; reflection is kept as a secondary path.
 */
class SoftApController(private val context: Context) {

    private val TAG = "SoftApController"

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

            var enabled = false
            try {
                val m: Method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                m.isAccessible = true
                enabled = m.invoke(wm) as Boolean
            } catch (_: Throwable) {
            }
            result.put("enabled", enabled)

            // Also try shell status (best-effort)
            try {
                val r = ShizukuHelper.runShell("cmd", "wifi", "status")
                if (r != null) {
                    result.put("shellStdout", r.second)
                }
            } catch (_: Throwable) {
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val config = getSoftApConfigurationReflect(wm)
                    if (config != null) {
                        putConfigInto(result, config)
                    } else {
                        result.put("configError", "Could not read SoftApConfiguration")
                    }
                } catch (e: Throwable) {
                    result.put("configError", e.message ?: "Could not read SoftApConfiguration")
                }
            } else {
                result.put("configError", "SoftApConfiguration requires Android 11+")
            }

            result.put("clients", JSONArray())
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
            val config = getSoftApConfigurationReflect(wm)
            if (config == null) {
                result.put("error", "Could not read SoftApConfiguration (permission / OEM restriction)")
                return result
            }
            putConfigInto(result, config)
        } catch (e: Throwable) {
            result.put("error", e.message ?: "Failed to get SoftApConfiguration")
        }
        return result
    }

    /**
     * Accepted keys: ssid, passphrase, band (2ghz|5ghz|6ghz|any),
     * hidden (bool), maxClients (int), securityType (open|wpa2|wpa3|wpa3_transition),
     * blockedClients (JSON array of MAC strings)
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

            val current = getSoftApConfigurationReflect(wm)
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = if (current != null) {
                builderClass.getConstructor(Class.forName("android.net.wifi.SoftApConfiguration"))
                    .newInstance(current)
            } else {
                builderClass.getConstructor().newInstance()
            }

            if (params.has("ssid")) {
                callBuilder(builder, "setSsid", arrayOf(String::class.java), params.getString("ssid"))
            }

            if (params.has("passphrase") || params.has("securityType")) {
                val pass = params.optString("passphrase", "")
                val secName = params.optString("securityType", "wpa2").lowercase()
                val secType = when (secName) {
                    "open" -> 0
                    "wpa3" -> 3
                    "wpa3_transition" -> 4
                    else -> 1
                }
                if (secType == 0) {
                    callBuilder(
                        builder,
                        "setPassphrase",
                        arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                        null,
                        secType
                    )
                } else {
                    callBuilder(
                        builder,
                        "setPassphrase",
                        arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                        pass,
                        secType
                    )
                }
            }

            if (params.has("hidden")) {
                callBuilder(
                    builder,
                    "setHiddenSsid",
                    arrayOf(Boolean::class.javaPrimitiveType!!),
                    params.getBoolean("hidden")
                )
            }

            if (params.has("maxClients") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callBuilder(
                    builder,
                    "setMaxNumberOfClients",
                    arrayOf(Int::class.javaPrimitiveType!!),
                    params.getInt("maxClients")
                )
            }

            if (params.has("band")) {
                val bandStr = params.getString("band").lowercase()
                val band = when (bandStr) {
                    "2ghz", "2.4ghz" -> 1
                    "5ghz" -> 2
                    "6ghz" -> 4
                    else -> 1 or 2
                }
                callBuilder(builder, "setBand", arrayOf(Int::class.javaPrimitiveType!!), band)
            }

            if (params.has("blockedClients") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val arr = params.getJSONArray("blockedClients")
                val macClass = Class.forName("android.net.MacAddress")
                val fromString = macClass.getMethod("fromString", String::class.java)
                val list = java.util.ArrayList<Any>()
                for (i in 0 until arr.length()) {
                    try {
                        list.add(fromString.invoke(null, arr.getString(i))!!)
                    } catch (_: Throwable) {
                    }
                }
                callBuilder(builder, "setBlockedClientList", arrayOf(java.util.List::class.java), list)
            }

            val newConfig = builderClass.getMethod("build").invoke(builder)
            val ok = setSoftApConfigurationReflect(wm, newConfig)
            result.put("success", ok)
            if (ok && newConfig != null) {
                putConfigInto(result, newConfig)
            } else if (!ok) {
                result.put(
                    "error",
                    "setSoftApConfiguration returned false (OEM may require different shell path)"
                )
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
            // Shizuku shell first (works on many devices)
            val r = ShizukuHelper.runShellFirstSuccess(
                arrayOf("cmd", "wifi", "start-softap"),
                arrayOf("svc", "wifi", "enable"), // weak fallback, not real softap
            )

            if (r != null && r.first == 0) {
                result.put("success", true)
                result.put("exitCode", r.first)
                result.put("stdout", r.second)
                result.put("stderr", r.third)
                result.put("method", "shizuku-shell")
                return result
            }

            // Reflection fallback
            val wm = wifiManager ?: run {
                result.put("success", false)
                result.put("error", "WifiManager unavailable")
                if (r != null) {
                    result.put("shellExit", r.first)
                    result.put("shellStderr", r.third)
                }
                return result
            }
            try {
                val method = wm.javaClass.getMethod(
                    "startSoftAp",
                    Class.forName("android.net.wifi.WifiConfiguration")
                )
                val ok = method.invoke(wm, null as Any?) as Boolean
                result.put("success", ok)
                result.put("method", "startSoftAp")
                if (!ok) {
                    result.put(
                        "error",
                        "startSoftAp returned false. Shell also failed: ${r?.third ?: "no shell"}"
                    )
                }
            } catch (e: Throwable) {
                result.put("success", false)
                result.put("error", "Could not start SoftAP: ${e.message}")
                if (r != null) {
                    result.put("shellExit", r.first)
                    result.put("shellStderr", r.third)
                }
            }
        } catch (e: Throwable) {
            result.put("success", false)
            result.put("error", e.message ?: "start failed")
        }
        return result
    }

    fun stop(): JSONObject {
        val result = JSONObject()
        try {
            val r = ShizukuHelper.runShellFirstSuccess(
                arrayOf("cmd", "wifi", "stop-softap"),
                arrayOf("cmd", "connectivity", "tethering-stop", "wifi"),
            )

            if (r != null && r.first == 0) {
                result.put("success", true)
                result.put("exitCode", r.first)
                result.put("stdout", r.second)
                result.put("stderr", r.third)
                result.put("method", "shizuku-shell")
                return result
            }

            val wm = wifiManager ?: run {
                result.put("success", false)
                result.put("error", "WifiManager unavailable")
                if (r != null) {
                    result.put("shellExit", r.first)
                    result.put("shellStderr", r.third)
                }
                return result
            }
            try {
                val method = wm.javaClass.getMethod("stopSoftAp")
                val ok = method.invoke(wm) as Boolean
                result.put("success", ok)
                result.put("method", "stopSoftAp")
                if (!ok) {
                    result.put(
                        "error",
                        "stopSoftAp returned false. Shell also failed: ${r?.third ?: "no shell"}"
                    )
                }
            } catch (e: Throwable) {
                result.put("success", false)
                result.put("error", "Could not stop SoftAP: ${e.message}")
                if (r != null) {
                    result.put("shellExit", r.first)
                    result.put("shellStderr", r.third)
                }
            }
        } catch (e: Throwable) {
            result.put("success", false)
            result.put("error", e.message ?: "stop failed")
        }
        return result
    }

    fun getConnectedClients(): JSONObject {
        val result = JSONObject()
        result.put("clients", JSONArray())
        result.put("note", "Live client list requires SoftApCallback (next iteration)")
        return result
    }

    fun banClient(mac: String): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                result.put("error", "Client blocking requires Android 12+")
                return result
            }
            val current = getConfig()
            if (current.has("error")) return current

            val blocked = mutableListOf<String>()
            val arr = current.optJSONArray("blockedClients")
            if (arr != null) {
                for (i in 0 until arr.length()) blocked.add(arr.getString(i))
            }
            if (!blocked.contains(mac)) blocked.add(mac)

            val params = JSONObject()
            params.put("blockedClients", JSONArray(blocked))
            return setConfig(params)
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
            val current = getConfig()
            if (current.has("error")) return current

            val blocked = mutableListOf<String>()
            val arr = current.optJSONArray("blockedClients")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getString(i)
                    if (m != mac) blocked.add(m)
                }
            }

            val params = JSONObject()
            params.put("blockedClients", JSONArray(blocked))
            return setConfig(params)
        } catch (e: Throwable) {
            result.put("error", e.message ?: "unbanClient failed")
        }
        return result
    }

    private fun getSoftApConfigurationReflect(wm: WifiManager): Any? {
        return try {
            val m = wm.javaClass.getMethod("getSoftApConfiguration")
            m.invoke(wm)
        } catch (e: Throwable) {
            Log.w(TAG, "getSoftApConfiguration failed", e)
            null
        }
    }

    private fun setSoftApConfigurationReflect(wm: WifiManager, config: Any?): Boolean {
        return try {
            val m = wm.javaClass.getMethod(
                "setSoftApConfiguration",
                Class.forName("android.net.wifi.SoftApConfiguration")
            )
            m.invoke(wm, config) as Boolean
        } catch (e: Throwable) {
            Log.w(TAG, "setSoftApConfiguration failed", e)
            false
        }
    }

    private fun callBuilder(builder: Any, method: String, types: Array<Class<*>>, vararg args: Any?) {
        val m = builder.javaClass.getMethod(method, *types)
        m.invoke(builder, *args)
    }

    private fun putConfigInto(target: JSONObject, config: Any) {
        try {
            val c = config.javaClass
            fun invokeStr(name: String): String {
                return try {
                    (c.getMethod(name).invoke(config) as? String) ?: ""
                } catch (_: Throwable) {
                    ""
                }
            }
            fun invokeInt(name: String): Int {
                return try {
                    c.getMethod(name).invoke(config) as? Int ?: -1
                } catch (_: Throwable) {
                    -1
                }
            }
            fun invokeBool(name: String): Boolean {
                return try {
                    c.getMethod(name).invoke(config) as? Boolean ?: false
                } catch (_: Throwable) {
                    false
                }
            }

            target.put("ssid", invokeStr("getSsid"))
            target.put("securityType", invokeInt("getSecurityType"))
            target.put("hidden", invokeBool("isHiddenSsid"))
            target.put("band", invokeInt("getBand"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.put("maxClients", invokeInt("getMaxNumberOfClients"))
                try {
                    val list = c.getMethod("getBlockedClientList").invoke(config) as? List<*>
                    val arr = JSONArray()
                    list?.forEach { mac ->
                        arr.put(mac?.toString() ?: "")
                    }
                    target.put("blockedClients", arr)
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            target.put("parseError", e.message ?: "config parse failed")
        }
    }
}