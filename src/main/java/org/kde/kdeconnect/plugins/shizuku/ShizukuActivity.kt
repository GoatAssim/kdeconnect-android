/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.Device
import org.kde.kdeconnect_tp.R

/**
 * Control panel for Shizuku-powered actions on this phone.
 * Opened from the device page via the plugin button.
 */
class ShizukuActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var deviceId: String? = null
    private var softAp: SoftApController? = null
    private var wifi: WifiController? = null
    private var bluetooth: BluetoothController? = null
    private var battery: BatteryController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")

        ShizukuHelper.init(this)
        softAp = SoftApController(this)
        wifi = WifiController(this)
        bluetooth = BluetoothController(this)
        battery = BatteryController(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)

        statusText = TextView(this).apply {
            textSize = 16f
            text = "Loading Shizuku status…"
        }
        root.addView(statusText)

        logText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 24)
            text = ""
        }
        root.addView(logText)

        fun addBtn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    try {
                        action()
                    } catch (e: Throwable) {
                        appendLog("Error: ${e.message}")
                        Toast.makeText(this@ShizukuActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        addBtn("Refresh status") { refreshStatus() }
        addBtn("Request Shizuku permission") {
            ShizukuHelper.requestPermission()
            Toast.makeText(this, "Check Shizuku app for permission prompt", Toast.LENGTH_LONG).show()
            refreshStatus()
        }

        root.addView(section("Battery"))
        addBtn("Battery info") { showJson(battery!!.getStatus()) }

        root.addView(section("Wi‑Fi"))
        addBtn("Wi‑Fi status") { showJson(wifi!!.getStatus()) }
        addBtn("Scan networks") { showJson(wifi!!.scanNetworks()) }
        addBtn("Enable Wi‑Fi") { showJson(wifi!!.setEnabled(true)) }
        addBtn("Disable Wi‑Fi") { showJson(wifi!!.setEnabled(false)) }

        root.addView(section("Bluetooth"))
        addBtn("Bluetooth status") { showJson(bluetooth!!.getStatus()) }
        addBtn("Enable Bluetooth") { showJson(bluetooth!!.setEnabled(true)) }
        addBtn("Disable Bluetooth") { showJson(bluetooth!!.setEnabled(false)) }

        root.addView(section("Hotspot"))
        addBtn("Hotspot status") { showJson(softAp!!.getStatus()) }
        addBtn("Hotspot config") { showJson(softAp!!.getConfig()) }
        addBtn("Start hotspot") { showJson(softAp!!.start()) }
        addBtn("Stop hotspot") { showJson(softAp!!.stop()) }

        setContentView(scroll)
        supportActionBar?.title = "Shizuku controls"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        refreshStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun section(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, 32, 0, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun refreshStatus() {
        val available = ShizukuHelper.isAvailable()
        val perm = ShizukuHelper.isPermissionGranted()
        val uid = ShizukuHelper.getUid()
        statusText.text = buildString {
            append("Shizuku running: ").append(if (available) "yes" else "NO – start Shizuku first").append('\n')
            append("Permission granted: ").append(if (perm) "yes" else "NO – tap Request permission").append('\n')
            append("UID: ").append(uid)
            if (ShizukuHelper.isRoot()) append(" (root)")
            if (ShizukuHelper.isShell()) append(" (shell/ADB)")
        }
    }

    private fun showJson(obj: JSONObject) {
        appendLog(obj.toString(2))
    }

    private fun appendLog(msg: String) {
        logText.text = msg
    }
}
