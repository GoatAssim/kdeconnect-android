/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Tailscale integration
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.tailscale

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R

/**
 * Tailscale config screen on the phone.
 * Set the desktop's Tailscale IP and see status.
 */
class TailscaleActivity : AppCompatActivity() {

    private lateinit var remoteIpInput: EditText
    private lateinit var selfIpInput: EditText
    private lateinit var statusText: TextView
    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)

        statusText = TextView(this).apply {
            textSize = 15f
            text = "Loading…"
        }
        root.addView(statusText)

        root.addView(label("Desktop Tailscale IP (100.x.x.x)"))
        remoteIpInput = EditText(this).apply {
            hint = "100.64.0.1"
            setSingleLine()
        }
        root.addView(remoteIpInput)

        root.addView(label("This phone's Tailscale IP (optional)"))
        selfIpInput = EditText(this).apply {
            hint = "100.64.0.2"
            setSingleLine()
        }
        root.addView(selfIpInput)

        root.addView(Button(this).apply {
            text = "Save & auto-add to custom devices"
            setOnClickListener { save() }
        })

        root.addView(Button(this).apply {
            text = "Announce my IP to desktop"
            setOnClickListener { announceSelf() }
        })

        root.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadFromPlugin() }
        })

        root.addView(TextView(this).apply {
            setPadding(0, 32, 0, 0)
            text = "When you save the desktop IP, it is written into the same list as “Add devices by IP”, so KDE Connect will keep trying that address over Tailscale without re-pairing."
            textSize = 13f
        })

        setContentView(scroll)
        supportActionBar?.title = "Tailscale"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadFromPlugin()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, 24, 0, 8)
    }

    private fun plugin(): TailscalePlugin? {
        val id = deviceId ?: return null
        val device = KdeConnect.getInstance().getDevice(id) ?: return null
        return device.getPlugin(TailscalePlugin::class.java) as? TailscalePlugin
    }

    private fun loadFromPlugin() {
        val p = plugin()
        if (p == null) {
            statusText.text = "Plugin not loaded for this device (enable Tailscale plugin & pair first)"
            return
        }
        remoteIpInput.setText(p.getRemoteTailscaleIp())
        selfIpInput.setText(p.getSelfTailscaleIp())
        statusText.text = "Remote (desktop) IP: ${p.getRemoteTailscaleIp().ifEmpty { "(not set)" }}\n" +
            "Self (phone) IP: ${p.getSelfTailscaleIp().ifEmpty { "(not set)" }}"
    }

    private fun save() {
        val p = plugin()
        if (p == null) {
            Toast.makeText(this, "Plugin not available", Toast.LENGTH_SHORT).show()
            return
        }
        val remote = remoteIpInput.text.toString().trim()
        val self = selfIpInput.text.toString().trim()

        // Re-use plugin packet path so auto-add runs
        val npRemote = NetworkPacket(TailscalePlugin.PACKET_TYPE)
        npRemote["action"] = "setRemoteIp"
        npRemote["ip"] = remote
        p.onPacketReceived(npRemote)

        val npSelf = NetworkPacket(TailscalePlugin.PACKET_TYPE)
        npSelf["action"] = "setSelfIp"
        npSelf["ip"] = self
        p.onPacketReceived(npSelf)

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        loadFromPlugin()
    }

    private fun announceSelf() {
        val p = plugin()
        if (p == null) {
            Toast.makeText(this, "Plugin not available", Toast.LENGTH_SHORT).show()
            return
        }
        val self = selfIpInput.text.toString().trim()
        if (self.isEmpty()) {
            Toast.makeText(this, "Set this phone's Tailscale IP first", Toast.LENGTH_SHORT).show()
            return
        }
        val np = NetworkPacket(TailscalePlugin.PACKET_TYPE)
        np["action"] = "selfIp"
        np["ip"] = self
        // Send to the paired desktop
        val id = deviceId ?: return
        val device = KdeConnect.getInstance().getDevice(id) ?: return
        device.sendPacket(np)
        Toast.makeText(this, "Announced to desktop", Toast.LENGTH_SHORT).show()
    }
}

