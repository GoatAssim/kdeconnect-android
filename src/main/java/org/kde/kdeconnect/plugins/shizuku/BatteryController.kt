/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * Provides detailed battery information.
 * Uses normal APIs primarily; Shizuku can be used later for deeper health/cycle data
 * if the device exposes it via privileged services.
 */
class BatteryController(private val context: Context) {

    fun getStatus(): JSONObject {
        val result = JSONObject()
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) {
                result.put("error", "Could not read battery intent")
                return result
            }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"

            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            result.put("currentCharge", percent)
            result.put("isCharging", plugged != 0)
            result.put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
            result.put("status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            })
            result.put("health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            })
            result.put("temperatureC", if (temperature > 0) temperature / 10.0 else -1)
            result.put("voltageMv", voltage)
            result.put("technology", technology)

            // Capacity / charge counter if available (API 21+)
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    result.put("capacity", capacity)
                    result.put("chargeCounterUa", chargeCounter)
                    result.put("currentNowUa", currentNow)
                }
            } catch (_: Throwable) {
                // ignore
            }

        } catch (e: Throwable) {
            result.put("error", e.message ?: "Unknown battery error")
        }
        return result
    }
}
