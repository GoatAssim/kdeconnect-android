/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class BluetoothController(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
        }

    fun getStatus(): JSONObject {
        val result = JSONObject()
        try {
            val a = adapter
            if (a == null) {
                result.put("error", "BluetoothAdapter unavailable")
                return result
            }

            result.put("enabled", a.isEnabled)
            result.put("state", when (a.state) {
                BluetoothAdapter.STATE_OFF -> "off"
                BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                BluetoothAdapter.STATE_ON -> "on"
                BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                else -> "unknown"
            })
            result.put("name", a.name ?: "")
            result.put("address", try { a.address } catch (_: SecurityException) { "permission_denied" })

            // Bonded devices
            val bonded = JSONArray()
            try {
                a.bondedDevices?.forEach { device ->
                    val d = JSONObject()
                    d.put("name", device.name ?: "")
                    d.put("address", device.address)
                    d.put("bondState", device.bondState)
                    d.put("type", device.type)
                    bonded.put(d)
                }
            } catch (e: SecurityException) {
                result.put("bondedError", "BLUETOOTH_CONNECT permission missing")
            }
            result.put("bondedDevices", bonded)

        } catch (e: Throwable) {
            result.put("error", e.message ?: "Bluetooth status error")
        }
        return result
    }

    fun setEnabled(enabled: Boolean): JSONObject {
        val result = JSONObject()
        try {
            val a = adapter
            if (a == null) {
                result.put("error", "BluetoothAdapter unavailable")
                return result
            }
            val ok = if (enabled) a.enable() else a.disable()
            result.put("success", ok)
            result.put("enabled", enabled)
        } catch (e: SecurityException) {
            result.put("error", "Missing BLUETOOTH_CONNECT / BLUETOOTH_ADMIN permission")
        } catch (e: Throwable) {
            result.put("error", e.message ?: "Failed to toggle Bluetooth")
        }
        return result
    }
}
