/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import org.kde.kdeconnect.KdeConnect.Companion.getInstance
import org.kde.kdeconnect.ui.compose.KdeTheme

class JarvisActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra("deviceId")
        val plugin = getInstance().getDevicePlugin(deviceId, JarvisPlugin::class.java)
        if (plugin == null) {
            finish()
            return
        }
        val device = getInstance().getDevice(deviceId) ?: run {
            finish()
            return
        }
        plugin.requestStatus()
        setContent {
            val error = plugin.lastError.value
            LaunchedEffect(error) {
                if (error.isNotEmpty()) {
                    Toast.makeText(this@JarvisActivity, error, Toast.LENGTH_LONG).show()
                    plugin.lastError.value = ""
                }
            }
            KdeTheme(this) {
                JarvisApp(
                    plugin = plugin,
                    deviceName = device.name,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                )
            }
        }
    }
}
