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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
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

/**
 * Call control from PC (no remote audio routing).
 * Dual-SIM aware: reports which SIM is ringing, and dial asks for a SIM.
 */
@LoadablePlugin
class CallBridgePlugin : Plugin() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber: String? = null
    private var lastSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var ringerMutedByUs = false
    private var previousRingerMode = AudioManager.RINGER_MODE_NORMAL

    private val mainHandler = Handler(Looper.getMainLooper())
    private var telephonyCallback: Any? = null
    private var legacyListener: PhoneStateListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) return

            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val intState = when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> TelephonyManager.CALL_STATE_IDLE
            }

            if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
                lastNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            }

            // Dual-SIM: subscription id if present
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sub = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    lastSubId = sub
                }
                val sub2 = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                if (sub2 != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    lastSubId = sub2
                }
            }

            onCallStateChanged(intState, lastNumber, lastSubId)
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
        // Broadcast (number often arrives on a second broadcast)
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.priority = 999
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }

        // More reliable listener API
        registerTelephonyListener()
        return true
    }

    override fun onDestroy() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        unregisterTelephonyListener()
        restoreRingerIfNeeded()
    }
private val subCallbacks = mutableListOf<Any>()

private fun registerTelephonyListener() {
    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE missing")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = sm.activeSubscriptionInfoList
            if (!list.isNullOrEmpty()) {
                for (info in list) {
                    val tmForSub = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                        .createForSubscriptionId(info.subscriptionId)
                    attachListener(tmForSub, info.subscriptionId)
                }
                return
            }
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        attachListener(tm, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    } catch (e: Throwable) {
        Log.e(TAG, "registerTelephonyListener failed", e)
    }
}

private fun attachListener(tm: TelephonyManager, subId: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                Log.i(TAG, "TelephonyCallback state=$state subId=$subId")
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    lastSubId = subId
                }
                onCallStateChanged(state, lastNumber, lastSubId)
            }
        }
        tm.registerTelephonyCallback(context.mainExecutor, cb)
        subCallbacks.add(cb)
        telephonyCallback = cb
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!phoneNumber.isNullOrBlank()) lastNumber = phoneNumber
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) lastSubId = subId
                onCallStateChanged(state, lastNumber, lastSubId)
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
        subCallbacks.add(listener)
    }
}

    private fun unregisterTelephonyListener() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    tm.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Throwable) {
        }
        telephonyCallback = null
        legacyListener = null
    }
private fun onCallStateChanged(state: Int, number: String?, subId: Int) {
    if (!number.isNullOrBlank()) lastNumber = number
    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) lastSubId = subId

    // Always emit on real state change
    if (state == lastState) {
        // Number arrived later while still ringing → update PC
        if (state == TelephonyManager.CALL_STATE_RINGING && !number.isNullOrBlank()) {
            sendCallEvent(state, lastNumber, lastSubId)
        }
        return
    }
    lastState = state
    sendCallEvent(state, lastNumber, lastSubId)
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
                "answer" -> reply["body"] = answerCall().toString()
                "decline", "end", "reject" -> reply["body"] = endCall().toString()
                "muteRinger" -> reply["body"] = muteRinger(true).toString()
                "unmuteRinger" -> reply["body"] = muteRinger(false).toString()
                "muteMic" -> reply["body"] = setMicMuted(true).toString()
                "unmuteMic" -> reply["body"] = setMicMuted(false).toString()
                "speakerOn" -> reply["body"] = setSpeaker(true).toString()
                "speakerOff" -> reply["body"] = setSpeaker(false).toString()
                "dial" -> {
                    val number = np.getString("number")
                    val subId = if (np.has("subscriptionId")) np.getInt("subscriptionId") else -1
                    reply["body"] = dial(number, subId).toString()
                }
                "status" -> reply["body"] = currentStatus().toString()
                "sims.list" -> reply["body"] = listSims().toString()
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

    private fun sendCallEvent(state: Int, number: String?, subId: Int) {
        val np = NetworkPacket(PACKET_TYPE)
        np["action"] = "event"

        val event = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
            else -> "idle"
        }
        np["event"] = event
        np["phoneNumber"] = number ?: ""

        val sim = simInfo(subId)
        np["subscriptionId"] = sim.optInt("subscriptionId", -1)
        np["simSlot"] = sim.optInt("simSlot", -1)
        np["simName"] = sim.optString("simName", "")
        np["simCarrier"] = sim.optString("carrierName", "")

        var contactName = number ?: ""
        var photoBase64 = ""

        if (!number.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lookup = ContactsHelper.phoneNumberLookup(context, number)
            if (!lookup.name.isNullOrBlank()) contactName = lookup.name!!
            photoBase64 = ContactsHelper.photoId64Encoded(context, lookup.photoId)
        }

        np["contactName"] = contactName
        if (photoBase64.isNotEmpty()) {
            np["phoneThumbnail"] = photoBase64
        }

        if (event == "idle") {
            np["isCancel"] = true
            restoreRingerIfNeeded()
        }

        Log.i(TAG, "sendCallEvent event=$event number=$number sim=${sim.optString("simName")}")
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
        val sim = simInfo(lastSubId)
        o.put("subscriptionId", sim.optInt("subscriptionId", -1))
        o.put("simSlot", sim.optInt("simSlot", -1))
        o.put("simName", sim.optString("simName", ""))
        o.put("simCarrier", sim.optString("carrierName", ""))
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        o.put("micMuted", am.isMicrophoneMute)
        @Suppress("DEPRECATION")
        o.put("speakerOn", am.isSpeakerphoneOn)
        o.put("ringerMuted", ringerMutedByUs)
        return o
    }

    private fun listSims(): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            o.put("sims", arr)
            o.put("count", 0)
            return o
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            o.put("error", "READ_PHONE_STATE permission missing")
            o.put("sims", arr)
            return o
        }
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = sm.activeSubscriptionInfoList
            list?.forEach { info ->
                val item = JSONObject()
                item.put("subscriptionId", info.subscriptionId)
                item.put("simSlot", info.simSlotIndex) // 0-based
                item.put("simName", info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}")
                item.put("carrierName", info.carrierName?.toString() ?: "")
                item.put("number", info.number ?: "")
                arr.put(item)
            }
        } catch (e: Throwable) {
            o.put("error", e.message ?: "listSims failed")
        }
        o.put("sims", arr)
        o.put("count", arr.length())
        return o
    }

    private fun simInfo(subId: Int): JSONObject {
        val o = JSONObject()
        o.put("subscriptionId", subId)
        o.put("simSlot", -1)
        o.put("simName", "")
        o.put("carrierName", "")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return o
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return o
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = sm.activeSubscriptionInfoList ?: return o
            val info = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                list.firstOrNull { it.subscriptionId == subId }
            } else {
                null
            }
            val use = info ?: list.firstOrNull()
            if (use != null) {
                o.put("subscriptionId", use.subscriptionId)
                o.put("simSlot", use.simSlotIndex)
                o.put("simName", use.displayName?.toString() ?: "SIM ${use.simSlotIndex + 1}")
                o.put("carrierName", use.carrierName?.toString() ?: "")
            }
        } catch (_: Throwable) {
        }
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
        if (ringerMutedByUs) muteRinger(false)
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

    private fun dial(number: String, subscriptionId: Int): JSONObject {
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
            val uri = Uri.fromParts("tel", number, null)
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            if (subscriptionId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val handle = phoneAccountHandleForSubId(subscriptionId)
                if (handle != null) {
                    val extras = Bundle()
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    telecom.placeCall(uri, extras)
                    o.put("success", true)
                    o.put("number", number)
                    o.put("subscriptionId", subscriptionId)
                    o.put("method", "placeCall+sim")
                    return o
                }
            }

            // Fallback: default SIM / dialer
            val intent = Intent(Intent.ACTION_CALL, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (subscriptionId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val handle = phoneAccountHandleForSubId(subscriptionId)
                if (handle != null) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
            }
            context.startActivity(intent)
            o.put("success", true)
            o.put("number", number)
            o.put("subscriptionId", subscriptionId)
            o.put("method", "ACTION_CALL")
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "dial failed")
            o
        }
    }

    private fun phoneAccountHandleForSubId(subId: Int): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = telecom.callCapablePhoneAccounts
            // Handles are often named with subscription id in id string
            accounts.firstOrNull { handle ->
                handle.id.contains(subId.toString())
            } ?: run {
                // Fallback: match by slot order
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId }
                if (info != null && info.simSlotIndex in accounts.indices) {
                    accounts[info.simSlotIndex]
                } else null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "phoneAccountHandleForSubId failed", e)
            null
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
                ContactsContract.CommonDataKinds.Phone.NUMBER
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
        Manifest.permission.READ_CALL_LOG, // needed on many phones to get incoming number
    )

    override val optionalPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
    )

    companion object {
        const val PACKET_TYPE = "kdeconnect.callbridge"
        private const val TAG = "CallBridgePlugin"
    }
}