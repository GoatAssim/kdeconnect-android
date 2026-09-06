/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Call Bridge
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
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ContactsHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class CallBridgePlugin : Plugin() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber: String? = null
    private var lastSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var ringerMutedByUs = false
    private var previousRingerMode = AudioManager.RINGER_MODE_NORMAL

    private val telephonyCallbacks = mutableListOf<TelephonyCallback>()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sub = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) lastSubId = sub
            }

            handleState(intState)
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
        // Never throw out of onCreate — if this fails, the plugin won't receive any packets
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Throwable) {
            Log.e(TAG, "registerReceiver failed", e)
        }
        try {
            registerCallListeners()
        } catch (e: Throwable) {
            Log.e(TAG, "registerCallListeners failed", e)
        }
        Log.i(TAG, "CallBridgePlugin created")
        return true
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        unregisterCallListeners()
        if (ringerMutedByUs) {
            try {
                muteRinger(false)
            } catch (_: Throwable) {
            }
        }
    }

    private fun registerCallListeners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted — listeners skipped")
            return
        }

        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val tms = mutableListOf<TelephonyManager>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val list = sm.activeSubscriptionInfoList
                if (!list.isNullOrEmpty()) {
                    for (info in list) {
                        tms.add(baseTm.createForSubscriptionId(info.subscriptionId))
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "list subscriptions failed", e)
            }
        }
        if (tms.isEmpty()) tms.add(baseTm)

        for (tm in tms) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            try {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                telephonyCallbacks.add(cb)
            } catch (e: Throwable) {
                Log.e(TAG, "registerTelephonyCallback failed", e)
            }
        }
    }

    private fun unregisterCallListeners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            for (cb in telephonyCallbacks) {
                try {
                    tm.unregisterTelephonyCallback(cb)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        telephonyCallbacks.clear()
    }

    private fun handleState(state: Int) {
        if (state == lastState) return
        lastState = state
        sendCallEvent(state)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE) return false

        val action = np.getString("action")
        Log.i(TAG, "onPacketReceived action=$action")

        val reply = NetworkPacket(PACKET_TYPE)
        reply["action"] = action
        reply["requestId"] = np.getString("requestId")

        try {
            when (action) {
                "ping" -> {
                    reply["body"] = JSONObject()
                        .put("success", true)
                        .put("plugin", "callbridge")
                        .put("message", "pong")
                        .toString()
                }
                "answer" -> reply["body"] = answerCall().toString()
                "decline", "end", "reject" -> reply["body"] = endCall().toString()
                "muteRinger" -> reply["body"] = muteRinger(true).toString()
                "unmuteRinger" -> reply["body"] = muteRinger(false).toString()
                "muteMic" -> reply["body"] = setMicMuted(true).toString()
                "unmuteMic" -> reply["body"] = setMicMuted(false).toString()
                "speakerOn" -> reply["body"] = setSpeaker(true).toString()
                "speakerOff" -> reply["body"] = setSpeaker(false).toString()
                "status" -> reply["body"] = currentStatus().toString()
                "sims.list" -> reply["body"] = listSims().toString()
                "contacts.list" -> {
                    val query = np.getString("query", "")
                    reply["body"] = listContacts(query).toString()
                }
                "dial" -> {
                    val number = np.getString("number")
                    val subId = np.getInt("subscriptionId", -1)
                    reply["body"] = dial(number, subId).toString()
                }
                else -> {
                    reply["error"] = "Unknown action: $action"
                    reply["body"] = JSONObject().put("success", false).put("error", "Unknown action: $action").toString()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "action failed: $action", e)
            reply["error"] = e.message ?: "error"
            reply["body"] = JSONObject().put("success", false).put("error", e.message ?: "error").toString()
        }

        device.sendPacket(reply)
        Log.i(TAG, "replied to $action")
        return true
    }

    private fun sendCallEvent(state: Int) {
        if (!isDeviceInitialized) return
        try {
            val np = NetworkPacket(PACKET_TYPE)
            np["action"] = "event"
            val event = when (state) {
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
                else -> "idle"
            }
            np["event"] = event
            np["phoneNumber"] = lastNumber ?: ""

            val sim = simInfo(lastSubId)
            np["subscriptionId"] = sim.optInt("subscriptionId", -1)
            np["simSlot"] = sim.optInt("simSlot", -1)
            np["simName"] = sim.optString("simName", "")
            np["simCarrier"] = sim.optString("carrierName", "")

            var contactName = lastNumber ?: ""
            if (!lastNumber.isNullOrBlank() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            ) {
                val lookup = ContactsHelper.phoneNumberLookup(context, lastNumber!!)
                if (!lookup.name.isNullOrBlank()) contactName = lookup.name!!
                val photo = ContactsHelper.photoId64Encoded(context, lookup.photoId)
                if (photo.isNotEmpty()) np["phoneThumbnail"] = photo
            }
            np["contactName"] = contactName
            if (event == "idle") np["isCancel"] = true

            device.sendPacket(np)
        } catch (e: Throwable) {
            Log.e(TAG, "sendCallEvent failed", e)
        }
    }

    private fun perm(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

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
        o.put("perm_phone", perm(Manifest.permission.READ_PHONE_STATE))
        o.put("perm_call", perm(Manifest.permission.CALL_PHONE))
        o.put("perm_answer", perm(Manifest.permission.ANSWER_PHONE_CALLS))
        o.put("perm_contacts", perm(Manifest.permission.READ_CONTACTS))
        o.put("perm_call_log", perm(Manifest.permission.READ_CALL_LOG))
        val sim = simInfo(lastSubId)
        o.put("simName", sim.optString("simName", ""))
        o.put("simSlot", sim.optInt("simSlot", -1))
        return o
    }

    private fun listSims(): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        o.put("perm_phone", perm(Manifest.permission.READ_PHONE_STATE))
        if (!perm(Manifest.permission.READ_PHONE_STATE)) {
            o.put("error", "READ_PHONE_STATE not granted")
            o.put("sims", arr)
            o.put("count", 0)
            return o
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val list = sm.activeSubscriptionInfoList
                list?.forEach { info ->
                    val item = JSONObject()
                    item.put("subscriptionId", info.subscriptionId)
                    item.put("simSlot", info.simSlotIndex)
                    item.put("simName", info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}")
                    item.put("carrierName", info.carrierName?.toString() ?: "")
                    item.put("number", info.number ?: "")
                    arr.put(item)
                }
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
        if (!perm(Manifest.permission.READ_PHONE_STATE)) return o
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return o
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = sm.activeSubscriptionInfoList ?: return o
            val use = list.firstOrNull { it.subscriptionId == subId } ?: list.firstOrNull()
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

    /**
     * Turns a raw contact's (account_type, account_name) into a stable "source" key the PC
     * side can group/filter on, plus a human-readable label. Kept generic on purpose: any
     * account type we don't specifically recognize still gets its own key/label derived from
     * whatever Android reports, rather than being lumped into one "Other" bucket, so filtering
     * works correctly for account types this code has never heard of.
     */
    private fun classifyContactSource(accountType: String?, accountName: String?): Pair<String, String> {
        if (accountType.isNullOrBlank()) {
            // No raw-contact account at all == stored locally on the device.
            return "phone" to "Phone"
        }
        val type = accountType.lowercase(Locale.ROOT)
        return when {
            type.contains("sim") -> "sim" to "SIM"
            type == "com.google" || type.contains("google") -> "google" to "Google"
            type.contains("exchange") || type.contains("eas") -> "exchange" to "Exchange"
            type.contains("whatsapp") -> "whatsapp" to "WhatsApp"
            type.contains("telegram") -> "telegram" to "Telegram"
            type.contains("skype") -> "skype" to "Skype"
            type.contains("samsung") || type.contains("osp") -> "samsung" to "Samsung account"
            else -> {
                // Unknown account type: key on the raw type so contacts from the same
                // account still group together, label with whatever's most readable.
                val label = accountName?.takeIf { it.isNotBlank() } ?: accountType
                accountType to label
            }
        }
    }

    private fun listContacts(query: String): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        o.put("perm_contacts", perm(Manifest.permission.READ_CONTACTS))
        if (!perm(Manifest.permission.READ_CONTACTS)) {
            o.put("error", "READ_CONTACTS not granted")
            o.put("contacts", arr)
            o.put("count", 0)
            return o
        }
        try {
            // ACCOUNT_TYPE/ACCOUNT_NAME live on RawContacts, but the Phone Data view is
            // joined against raw_contacts so they can be requested directly here.
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME
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
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ).use { cursor ->
                var n = 0
                while (cursor != null && cursor.moveToNext() && n < 300) {
                    val name = cursor.getString(0) ?: ""
                    val number = cursor.getString(1) ?: ""
                    if (number.isBlank()) continue
                    val accountType = cursor.getString(2)
                    val accountName = cursor.getString(3)
                    val (sourceKey, sourceLabel) = classifyContactSource(accountType, accountName)
                    arr.put(
                        JSONObject()
                            .put("name", name)
                            .put("number", number)
                            .put("accountType", accountType ?: "")
                            .put("accountName", accountName ?: "")
                            .put("source", sourceKey)
                            .put("sourceLabel", sourceLabel)
                    )
                    n++
                }
            }
        } catch (e: Throwable) {
            o.put("error", e.message ?: "contacts query failed")
        }
        o.put("contacts", arr)
        o.put("count", arr.length())
        return o
    }

    private fun answerCall(): JSONObject {
        val o = JSONObject()
        if (!perm(Manifest.permission.ANSWER_PHONE_CALLS)) {
            o.put("success", false)
            o.put("error", "ANSWER_PHONE_CALLS not granted")
            return o
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
                o.put("success", true)
            } else {
                o.put("success", false)
                o.put("error", "Need Android 8+")
            }
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "answer failed")
            o
        }
    }

    private fun endCall(): JSONObject {
        val o = JSONObject()
        if (!perm(Manifest.permission.ANSWER_PHONE_CALLS)) {
            o.put("success", false)
            o.put("error", "ANSWER_PHONE_CALLS not granted")
            return o
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                o.put("success", tm.endCall())
            } else {
                o.put("success", false)
                o.put("error", "Need Android 9+")
            }
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "endCall failed")
            o
        }
    }

    private fun muteRinger(mute: Boolean): JSONObject {
        val o = JSONObject()
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    private fun setMicMuted(muted: Boolean): JSONObject {
        val o = JSONObject()
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = muted
            o.put("success", true)
            o.put("micMuted", muted)
            o
        } catch (e: Throwable) {
            o.put("success", false)
            o.put("error", e.message ?: "mic failed")
            o
        }
    }

    private fun setSpeaker(on: Boolean): JSONObject {
        val o = JSONObject()
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
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
        if (!perm(Manifest.permission.CALL_PHONE)) {
            o.put("success", false)
            o.put("error", "CALL_PHONE not granted")
            return o
        }
        return try {
            val uri = Uri.fromParts("tel", number, null)
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle()
            if (subscriptionId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                phoneAccountHandleForSubId(subscriptionId)?.let {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
                }
            }
            // placeCall works from background; startActivity often does not
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                telecom.placeCall(uri, extras)
            } else {
                val intent = Intent(Intent.ACTION_CALL, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            o.put("success", true)
            o.put("number", number)
            o.put("subscriptionId", subscriptionId)
            o.put("method", "placeCall")
            o
        } catch (e: Throwable) {
            Log.e(TAG, "dial failed", e)
            o.put("success", false)
            o.put("error", e.message ?: "dial failed")
            o
        }
    }

    private fun phoneAccountHandleForSubId(subId: Int): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (!perm(Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = telecom.callCapablePhoneAccounts
            accounts.firstOrNull { it.id.contains(subId.toString()) } ?: run {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId }
                if (info != null && info.simSlotIndex in accounts.indices) accounts[info.simSlotIndex] else null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "phoneAccountHandleForSubId", e)
            null
        }
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
    Manifest.permission.READ_CALL_LOG, // only needed on some phones to get the incoming number; must not block ringing detection, SIM listing or dialing
)

    companion object {
        const val PACKET_TYPE = "kdeconnect.callbridge"
        private const val TAG = "CallBridgePlugin"
    }
}