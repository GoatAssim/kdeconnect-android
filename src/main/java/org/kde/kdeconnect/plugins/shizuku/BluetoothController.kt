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
            result.put(
                "state",
                when (a.state) {
                    BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                    BluetoothAdapter.STATE_ON -> "on"
                    BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                    else -> "unknown"
                }
            )
            result.put("name", a.name ?: "")
            result.put(
                "address",
                try {
                    a.address
                } catch (_: SecurityException) {
                    "permission_denied"
                }
            )

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
            val svcArg = if (enabled) "enable" else "disable"

            val r = ShizukuHelper.runShellFirstSuccess(
                arrayOf("svc", "bluetooth", svcArg),
                arrayOf("cmd", "bluetooth_manager", "enable"), // enable-only variant on some ROMs
            )

            // For disable, only svc is reliable; if enable path used wrong cmd when disabling, fix:
            val shellResult = if (enabled) {
                r
            } else {
                ShizukuHelper.runShell("svc", "bluetooth", "disable")
            }

            if (shellResult == null) {
                val a = adapter
                if (a == null) {
                    result.put("success", false)
                    result.put("error", "Shizuku not available and BluetoothAdapter unavailable")
                    return result
                }
                val ok = if (enabled) a.enable() else a.disable()
                result.put("success", ok)
                result.put("enabled", enabled)
                result.put("method", "BluetoothAdapter")
                if (!ok) {
                    result.put(
                        "error",
                        "BluetoothAdapter enable/disable returned false (need Shizuku running + permission)"
                    )
                }
                return result
            }

            val (exit, stdout, stderr) = shellResult
            result.put("success", exit == 0)
            result.put("exitCode", exit)
            result.put("stdout", stdout)
            result.put("stderr", stderr)
            result.put("enabled", enabled)
            result.put("method", "shizuku-shell")
            if (exit != 0) {
                result.put("error", stderr.ifBlank { "bluetooth toggle failed with code $exit" })
            }
        } catch (e: SecurityException) {
            result.put("success", false)
            result.put("error", "Missing BLUETOOTH_CONNECT / BLUETOOTH_ADMIN permission")
        } catch (e: Throwable) {
            result.put("success", false)
            result.put("error", e.message ?: "Failed to toggle Bluetooth")
        }
        return result
    }
}