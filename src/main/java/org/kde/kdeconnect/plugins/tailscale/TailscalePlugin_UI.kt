/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Tailscale integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.tailscale

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

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

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.tailscale_button),
            android.R.drawable.ic_menu_share,
        ) { parentActivity ->
            val intent = Intent(parentActivity, TailscaleActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        },
    )

    override fun onCreate(): Boolean {
        val remoteIp = getRemoteTailscaleIp()
        if (remoteIp.isNotEmpty()) {
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
                "status" -> reply["body"] = getLocalStatus().toString()
                "getRemoteIp" -> {
                    reply["body"] = JSONObject()
                        .put("remoteIp", getRemoteTailscaleIp())
                        .put("selfIp", getSelfTailscaleIp())
                        .toString()
                }
                "setRemoteIp" -> {
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip) || ip.isEmpty()) {
                        setRemoteTailscaleIp(ip)
                        if (ip.isNotEmpty()) addToCustomDevices(ip)
                        reply["body"] = JSONObject().put("success", true).put("remoteIp", ip).toString()
                        if (ip.isNotEmpty()) sendRemoteIpAnnouncement(ip)
                    } else {
                        reply["error"] = "Invalid Tailscale IP (expected 100.x.x.x)"
                    }
                }
                "setSelfIp" -> {
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip) || ip.isEmpty()) {
                        setSelfTailscaleIp(ip)
                        reply["body"] = JSONObject().put("success", true).put("selfIp", ip).toString()
                    } else {
                        reply["error"] = "Invalid Tailscale IP"
                    }
                }
                "up", "down" -> {
                    reply["body"] = JSONObject()
                        .put("success", false)
                        .put("message", "Use the Tailscale app or Quick Settings tile on the phone")
                        .toString()
                }
                "selfIp" -> {
                    val ip = np.getString("ip").trim()
                    if (isValidTailscaleIp(ip)) {
                        setRemoteTailscaleIp(ip)
                        addToCustomDevices(ip)
                        reply["body"] = JSONObject()
                            .put("stored", true)
                            .put("addedToCustomDevices", true)
                            .put("remoteIp", ip)
                            .toString()
                        Log.i(TAG, "Auto-added desktop Tailscale IP: $ip")
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

    fun getRemoteTailscaleIp(): String = prefs.getString(KEY_REMOTE_IP, "") ?: ""
    fun setRemoteTailscaleIp(ip: String) = prefs.edit().putString(KEY_REMOTE_IP, ip).apply()
    fun getSelfTailscaleIp(): String = prefs.getString(KEY_SELF_IP, "") ?: ""
    fun setSelfTailscaleIp(ip: String) = prefs.edit().putString(KEY_SELF_IP, ip).apply()

    private fun addToCustomDevices(ip: String) {
        if (ip.isEmpty()) return
        try {
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val key = "device_list_preference"
            val current = defaultPrefs.getString(key, "") ?: ""
            val list = if (current.isEmpty()) {
                mutableListOf()
            } else {
                current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            }
            if (!list.contains(ip)) {
                list.add(ip)
                defaultPrefs.edit().putString(key, list.joinToString(",")).apply()
                Log.i(TAG, "Added $ip to Android custom device list")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add $ip to custom devices", e)
        }
    }

    private fun isValidTailscaleIp(ip: String): Boolean {
        if (!ip.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return false
        val parts = ip.split(".").map { it.toIntOrNull() ?: return false }
        if (parts[0] != 100) return false
        if (parts[1] < 64 || parts[1] > 127) return false
        return parts.all { it in 0..255 }
    }

    private fun getLocalStatus(): JSONObject {
        return JSONObject()
            .put("remoteIp", getRemoteTailscaleIp())
            .put("selfIp", getSelfTailscaleIp())
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
