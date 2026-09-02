/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Call Bridge
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.callbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ContactsHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import android.provider.ContactsContract

/**
 * Call control from the PC (no remote audio routing).
 *
 * Incoming: event packets with number/name/photo.
 * Commands: answer, decline/end, muteRinger, muteMic, speaker, dial, contacts.list
 */
@LoadablePlugin
class CallBridgePlugin : Plugin() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber: String? = null
    private var ringerMutedByUs = false
    private var previousRingerMode = AudioManager.RINGER_MODE_NORMAL

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) return

            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val intState = when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> TelephonyManager.CALL_STATE_IDLE
            }

            // Prefer broadcast that includes the number
            if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
                lastNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            }

            if (intState != lastState) {
                lastState = intState
                sendCallEvent(intState, lastNumber)
            }
        }
    }

    override val displayName: String
        get() = try {
            context.getString(R.string.pref_plugin_callbridge)
        } catch (_: Exception) {
            "Call Bridge"
        }

    override val description: String
        get() = try {
            context.getString(R.string.pref_plugin_callbridge_desc)
        } catch (_: Exception) {
            "Answer, decline and place calls from your computer"
        }

    override val isEnabledByDefault: Boolean = false

    override fun onCreate(): Boolean {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.priority = 999
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        return true
    }

    override fun onDestroy() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        restoreRingerIfNeeded()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE) return false
        val action = np.getString("action")
        Log.d(TAG, "action=$action")

        val reply = NetworkPacket(PACKET_TYPE)
        reply["action"] = action
        reply["requestId"] = np.getString("requestId")

        try {
            when (action) {
                "answer" -> {
                    reply["body"] = answerCall().toString()
                }
                "decline", "end", "reject" -> {
                    reply["body"] = endCall().toString()
                }
                "muteRinger" -> {
                    reply["body"] = muteRinger(true).toString()
                }
                "unmuteRinger" -> {
                    reply["body"] = muteRinger(false).toString()
                }
                "muteMic" -> {
                    reply["body"] = setMicMuted(true).toString()
                }
                "unmuteMic" -> {
                    reply["body"] = setMicMuted(false).toString()
                }
                "speakerOn" -> {
                    reply["body"] = setSpeaker(true).toString()
                }
                "speakerOff" -> {
                    reply["body"] = setSpeaker(false).toString()
                }
                "dial" -> {
                    val number = np.getString("number")
                    reply["body"] = dial(number).toString()
                }
                "status" -> {
                    reply["body"] = currentStatus().toString()
                }
                "contacts.list" -> {
                    val query = np.getString("query", "")
                    reply["body"] = listContacts(query).toString()
                }
                else -> reply["error"] = "Unknown action: $action"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error $action", e)
            reply["error"] = e.message ?: "error"
        }

        device.sendPacket(reply)
        return true
    }

    private fun sendCallEvent(state: Int, number: String?) {
        val np = NetworkPacket(PACKET_TYPE)
        np["action"] = "event"

        val event = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
            else -> "idle"
        }
        np["event"] = event
        np["phoneNumber"] = number ?: ""

        var contactName = number ?: ""
        var photoBase64 = ""

        if (!number.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lookup = ContactsHelper.phoneNumberLookup(context, number)
            if (!lookup.name.isNullOrBlank()) {
                contactName = lookup.name!!
            }
            photoBase64 = ContactsHelper.photoId64Encoded(context, lookup.photoId)
        }

        np["contactName"] = contactName
        if (photoBase64.isNotEmpty()) {
            np["phoneThumbnail"] = photoBase64
        }

        // Cancel flag used by desktop to close popup when idle
        if (event == "idle") {
            np["isCancel"] = true
            restoreRingerIfNeeded()
        }

        device.sendPacket(np)
    }

    private fun currentStatus(): JSONObject {
        val o = JSONObject()
        o.put(
            "state",
            when (lastState) {
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
                else -> "idle"
            }
        )
        o.put("phoneNumber", lastNumber ?: "")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        o.put("micMuted", am.isMicrophoneMute)
        @Suppress("DEPRECATION")
        o.put("speakerOn", am.isSpeakerphoneOn)
        o.put("ringerMuted", ringerMutedByUs)
        return o
    }

    private fun answerCall(): JSONObject {
        val o = JSONObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            o.put("success", false)
            o.put("error", "Answer requires Android 8+")
            return o
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            o.put("success", false)
            o.put("error", "ANSWER_PHONE_CALLS permission missing")
            return o
        }
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            o.put("success", true)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "answer failed")
            o
        }
    }

    private fun endCall(): JSONObject {
        val o = JSONObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            o.put("success", false)
            o.put("error", "End/reject requires Android 9+")
            return o
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            o.put("success", false)
            o.put("error", "ANSWER_PHONE_CALLS permission missing")
            return o
        }
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val ok = tm.endCall()
            o.put("success", ok)
            restoreRingerIfNeeded()
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "endCall failed")
            o
        }
    }

    private fun muteRinger(mute: Boolean): JSONObject {
        val o = JSONObject()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            if (mute) {
                previousRingerMode = am.ringerMode
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
                ringerMutedByUs = true
            } else {
                am.ringerMode = previousRingerMode
                ringerMutedByUs = false
            }
            o.put("success", true)
            o.put("muted", mute)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "muteRinger failed")
            o
        }
    }

    private fun restoreRingerIfNeeded() {
        if (ringerMutedByUs) {
            muteRinger(false)
        }
    }

    private fun setMicMuted(muted: Boolean): JSONObject {
        val o = JSONObject()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.isMicrophoneMute = muted
            o.put("success", true)
            o.put("micMuted", muted)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "mic mute failed")
            o
        }
    }

    private fun setSpeaker(on: Boolean): JSONObject {
        val o = JSONObject()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            // Keep in-call mode so speaker sticks during a call
            if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                am.mode = AudioManager.MODE_IN_CALL
            }
            o.put("success", true)
            o.put("speakerOn", on)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "speaker failed")
            o
        }
    }

    private fun dial(number: String): JSONObject {
        val o = JSONObject()
        if (number.isBlank()) {
            o.put("success", false)
            o.put("error", "empty number")
            return o
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            o.put("success", false)
            o.put("error", "CALL_PHONE permission missing")
            return o
        }
        return try {
            // Supports normal numbers and *# USSD-style codes
            val uri = Uri.fromParts("tel", number, null)
            val intent = Intent(Intent.ACTION_CALL, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            o.put("success", true)
            o.put("number", number)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "dial failed")
            o
        }
    }

    private fun listContacts(query: String): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            o.put("error", "READ_CONTACTS permission missing")
            o.put("contacts", arr)
            return o
        }
        try {
            val cr = context.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val selection: String?
            val args: Array<String>?
            if (query.isBlank()) {
                selection = null
                args = null
            } else {
                selection =
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                args = arrayOf("%$query%", "%$query%")
            }
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ).use { cursor ->
                var count = 0
                while (cursor != null && cursor.moveToNext() && count < 200) {
                    val name = cursor.getString(0) ?: ""
                    val number = cursor.getString(1) ?: ""
                    if (number.isBlank()) continue
                    val item = JSONObject()
                    item.put("name", name)
                    item.put("number", number)
                    arr.put(item)
                    count++
                }
            }
            o.put("contacts", arr)
            o.put("count", arr.length())
        } catch (e: Throwable) {
            o.put("error", e.message ?: "contacts failed")
            o.put("contacts", arr)
        }
        return o
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE)

    override val requiredPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    override val optionalPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
    )

    companion object {
        const val PACKET_TYPE = "kdeconnect.callbridge"
        private const val TAG = "CallBridgePlugin"
    }
}