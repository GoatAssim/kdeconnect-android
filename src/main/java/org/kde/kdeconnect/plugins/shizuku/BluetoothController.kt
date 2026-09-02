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
            } catch (_: SecurityException) {
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

        val svcArg = if (enabled) "enable" else "disable"
        val r = ShizukuHelper.runShell("svc", "bluetooth", svcArg)

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
        result.put("method", "shizuku-shell svc bluetooth $svcArg")
        if (exit != 0) {
            result.put("error", stderr.ifBlank { "svc bluetooth $svcArg failed code $exit" })
        }
        return result
    }
}