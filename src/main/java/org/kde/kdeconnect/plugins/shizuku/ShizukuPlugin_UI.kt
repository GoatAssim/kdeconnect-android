/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Intent
import android.util.Log
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * ShizukuPlugin – privileged phone control + UI button on device page.
 *
 * Packet type: kdeconnect.shizuku
 */
@LoadablePlugin
class ShizukuPlugin : Plugin() {

    private lateinit var battery: BatteryController
    private lateinit var wifi: WifiController
    private lateinit var bluetooth: BluetoothController
    private lateinit var softAp: SoftApController
    private lateinit var packages: PackageController

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_shizuku)

    override val description: String
        get() = context.getString(R.string.pref_plugin_shizuku_desc)

    override val isEnabledByDefault: Boolean = false

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.shizuku_button),
            android.R.drawable.ic_menu_manage,
        ) { parentActivity ->
            val intent = Intent(parentActivity, ShizukuActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        },
    )

    override fun onCreate(): Boolean {
        ShizukuHelper.init(context)
        battery = BatteryController(context)
        wifi = WifiController(context)
        bluetooth = BluetoothController(context)
        softAp = SoftApController(context)
        packages = PackageController(context)
        return true
    }

    override fun onDestroy() {
        ShizukuHelper.destroy()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE) return false

        val action = np.getString("action")
        Log.d(TAG, "Received action: $action")

        val reply = NetworkPacket(PACKET_TYPE)
        reply["action"] = action
        reply["requestId"] = np.getString("requestId")

        try {
            when (action) {
                "status" -> {
                    val status = JSONObject()
                    status.put("shizukuAvailable", ShizukuHelper.isAvailable())
                    status.put("shizukuPermission", ShizukuHelper.isPermissionGranted())
                    status.put("uid", ShizukuHelper.getUid())
                    status.put("isRoot", ShizukuHelper.isRoot())
                    status.put("isShell", ShizukuHelper.isShell())
                    reply["body"] = status.toString()
                }
                "battery" -> reply["body"] = battery.getStatus().toString()
                "wifi" -> reply["body"] = wifi.getStatus().toString()
                "wifi.scan" -> reply["body"] = wifi.scanNetworks().toString()
                "wifi.enable" -> reply["body"] = wifi.setEnabled(true).toString()
                "wifi.disable" -> reply["body"] = wifi.setEnabled(false).toString()
                "bluetooth" -> reply["body"] = bluetooth.getStatus().toString()
                "bluetooth.enable" -> reply["body"] = bluetooth.setEnabled(true).toString()
                "bluetooth.disable" -> reply["body"] = bluetooth.setEnabled(false).toString()
                "hotspot" -> reply["body"] = softAp.getStatus().toString()
                "hotspot.getConfig" -> reply["body"] = softAp.getConfig().toString()
                "hotspot.setConfig" -> {
                    val params = try {
                        JSONObject(np.getString("params"))
                    } catch (_: Throwable) {
                        JSONObject()
                    }
                    reply["body"] = softAp.setConfig(params).toString()
                }
                "hotspot.start" -> reply["body"] = softAp.start().toString()
                "hotspot.stop" -> reply["body"] = softAp.stop().toString()
                "hotspot.clients" -> reply["body"] = softAp.getConnectedClients().toString()
                "hotspot.ban" -> reply["body"] = softAp.banClient(np.getString("mac")).toString()
                "hotspot.unban" -> reply["body"] = softAp.unbanClient(np.getString("mac")).toString()
                "packages.list" -> {
                    val userOnly = np.getBoolean("userOnly", true)
                    reply["body"] = packages.listPackages(userOnly).toString()
                }
                "packages.install" -> reply["body"] = packages.installApk(np.getString("path")).toString()
                "packages.uninstall" -> reply["body"] = packages.uninstall(np.getString("packageName")).toString()
                "requestPermission" -> {
                    ShizukuHelper.requestPermission()
                    reply["body"] = JSONObject().put("requested", true).toString()
                }
                else -> reply["error"] = "Unknown action: $action"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling action $action", e)
            reply["error"] = e.message ?: "Internal plugin error"
        }

        device.sendPacket(reply)
        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE)

    companion object {
        const val PACKET_TYPE = "kdeconnect.shizuku"
        private const val TAG = "ShizukuPlugin"
    }
}
