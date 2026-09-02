/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Tailscale integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * TailscalePlugin (Android)
 *
 * - Stores the Tailscale IP of the paired desktop.
 * - Automatically adds that IP to the same custom-device list that
 *   "Add devices by IP" uses, so the phone will keep trying the PC
 *   over Tailscale without re-pairing.
 * - Also accepts the desktop announcing its own Tailscale IP.
 *
 * Packet type: kdeconnect.tailscale
 */
@LoadablePlugin
class TailscalePlugin : Plugin() {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("tailscale_plugin_${device.deviceId}", Context.MODE_PRIVATE)

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_tailscale)

    override val description: String
        get() = context.getString(R.string.pref_plugin_tailscale_desc)

    override val isEnabledByDefault: Boolean = true

    override fun hasSettings(): Boolean = true

    override fun onCreate(): Boolean {
        val remoteIp = getRemoteTailscaleIp()
        if (remoteIp.isNotEmpty()) {
            // Make sure it is still present in the global custom-device list
            addToCustomDevices(remoteIp)
            sendRemoteIpAnnouncement(remoteIp)
        }
        return true
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE) return false

        val action = np.getString("action")
        val reply = NetworkPacket(PACKET_TYPE)
        reply["action"] = action
        reply["requestId"] = np.getString("requestId")

        try {
            when (action) {
                "status" -> {
                    reply["body"] = getLocalStatus().toString()
                }
                "getRemoteIp" -> {
                    val obj = JSONObject()
                    obj.put("remoteIp", getRemoteTailscaleIp())
                    obj.put("selfIp", getSelfTailscaleIp())
                    reply["body"] = obj.toString()
                }
                "setRemoteIp" -> {
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip) || ip.isEmpty()) {
                        setRemoteTailscaleIp(ip)
                        if (ip.isNotEmpty()) {
                            addToCustomDevices(ip)
                        }
                        reply["body"] = JSONObject()
                            .put("success", true)
                            .put("remoteIp", ip)
                            .toString()
                        if (ip.isNotEmpty()) sendRemoteIpAnnouncement(ip)
                    } else {
                        reply["error"] = "Invalid Tailscale IP (expected 100.x.x.x)"
                    }
                }
                "setSelfIp" -> {
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip) || ip.isEmpty()) {
                        setSelfTailscaleIp(ip)
                        reply["body"] = JSONObject()
                            .put("success", true)
                            .put("selfIp", ip)
                            .toString()
                    } else {
                        reply["error"] = "Invalid Tailscale IP"
                    }
                }
                "up" -> {
                    reply["body"] = controlTailscale(true).toString()
                }
                "down" -> {
                    reply["body"] = controlTailscale(false).toString()
                }
                "selfIp" -> {
                    // Desktop is telling us its Tailscale IP → store + auto-add
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip)) {
                        setRemoteTailscaleIp(ip)
                        addToCustomDevices(ip)
                        reply["body"] = JSONObject()
                            .put("stored", true)
                            .put("addedToCustomDevices", true)
                            .put("remoteIp", ip)
                            .toString()
                        Log.i(TAG, "Auto-added desktop Tailscale IP to custom devices: $ip")
                    } else {
                        reply["error"] = "Invalid IP received"
                    }
                }
                else -> reply["error"] = "Unknown action: $action"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling $action", e)
            reply["error"] = e.message ?: "Internal error"
        }

        device.sendPacket(reply)
        return true
    }

    // ------------------------------------------------------------------
    // Persistence (per-device)
    // ------------------------------------------------------------------

    fun getRemoteTailscaleIp(): String = prefs.getString(KEY_REMOTE_IP, "") ?: ""
    fun setRemoteTailscaleIp(ip: String) = prefs.edit().putString(KEY_REMOTE_IP, ip).apply()

    fun getSelfTailscaleIp(): String = prefs.getString(KEY_SELF_IP, "") ?: ""
    fun setSelfTailscaleIp(ip: String) = prefs.edit().putString(KEY_SELF_IP, ip).apply()

    // ------------------------------------------------------------------
    // Auto-add to the same list used by "Add devices by IP"
    // ------------------------------------------------------------------

    /**
     * Writes the IP into the preference that CustomDevicesActivity / LanLinkProvider
     * already read. This is the Android equivalent of desktop's customDevices.
     */
    private fun addToCustomDevices(ip: String) {
        if (ip.isEmpty()) return

        try {
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val key = "device_list_preference"          // same key CustomDevicesActivity uses
            val current = defaultPrefs.getString(key, "") ?: ""

            val list = if (current.isEmpty()) {
                mutableListOf()
            } else {
                current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            }

            if (!list.contains(ip)) {
                list.add(ip)
                val serialized = list.joinToString(",")
                defaultPrefs.edit().putString(key, serialized).apply()
                Log.i(TAG, "Added $ip to Android custom device list → $serialized")
            } else {
                Log.d(TAG, "$ip already present in custom device list")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add $ip to custom devices", e)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isValidTailscaleIp(ip: String): Boolean {
        if (!ip.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return false
        val parts = ip.split(".").map { it.toIntOrNull() ?: return false }
        if (parts[0] != 100) return false
        if (parts[1] < 64 || parts[1] > 127) return false
        return parts.all { it in 0..255 }
    }

    private fun getLocalStatus(): JSONObject {
        val obj = JSONObject()
        obj.put("remoteIp", getRemoteTailscaleIp())
        obj.put("selfIp", getSelfTailscaleIp())
        obj.put("tailscaleInstalled", isTailscaleInstalled())
        obj.put("inCustomDevices", isInCustomDevices(getRemoteTailscaleIp()))
        return obj
    }

    private fun isInCustomDevices(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val current = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("device_list_preference", "") ?: ""
        return current.split(",").map { it.trim() }.contains(ip)
    }

    private fun isTailscaleInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.tailscale.ipn", 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun controlTailscale(up: Boolean): JSONObject {
        val result = JSONObject()
        result.put("success", false)
        result.put(
            "message",
            if (up)
                "Please enable Tailscale from the Tailscale app or Quick Settings tile"
            else
                "Please disable Tailscale from the Tailscale app or Quick Settings tile"
        )
        result.put("installed", isTailscaleInstalled())
        return result
    }

    private fun sendRemoteIpAnnouncement(ip: String) {
        val np = NetworkPacket(PACKET_TYPE)
        np["action"] = "selfIp"
        np["ip"] = ip
        device.sendPacket(np)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE)

    companion object {
        const val PACKET_TYPE = "kdeconnect.tailscale"
        private const val TAG = "TailscalePlugin"
        private const val KEY_REMOTE_IP = "remote_tailscale_ip"
        private const val KEY_SELF_IP = "self_tailscale_ip"
    }
}
