/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.util.regex.Pattern

@LoadablePlugin
class JarvisPlugin : Plugin() {

    val online = mutableStateOf(false)
    val statusError = mutableStateOf("")
    val commandsJson = mutableStateOf("{}")
    val lastError = mutableStateOf("")
    val runOutput = mutableStateListOf<JarvisOutputLine>()
    val askMessages = mutableStateListOf<JarvisChatMessage>()
    val askConsole = mutableStateListOf<JarvisOutputLine>()
    val configTexts = mutableStateMapOf<String, String>()
    val configPaths = mutableStateMapOf<String, String>()
    val runBusy = mutableStateOf(false)
    val askBusy = mutableStateOf(false)
    val muted = mutableStateOf(false)
    val sequence = mutableStateListOf<JarvisSequenceItem>()
    private val jobId = mutableIntStateOf(1)
    private var assistantBuffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_jarvis)

    override val description: String
        get() = context.getString(R.string.pref_plugin_jarvis_desc)

    override val isEnabledByDefault: Boolean = true

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.jarvis_ask_button),
            R.drawable.jarvis_plugin_24dp,
        ) { parentActivity ->
            val intent = Intent(parentActivity, JarvisActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        },
    )

    override fun onCreate(): Boolean {
        muted.value = prefs.getBoolean("muted", false)
        JarvisSpeech.ensure(context)
        requestStatus()
        return true
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.getString("type")) {
            "status" -> {
                val path = np.getString("configPath")
                val onlineFlag = np.getBoolean("online", false) || path.isNotEmpty()
                onMain {
                    online.value = onlineFlag
                    statusError.value = np.getString("error")
                }
                return true
            }
            "commands" -> {
                val err = np.getString("error")
                val json = np.getString("commandsJson").ifEmpty { "{}" }
                onMain {
                    if (err.isNotEmpty()) {
                        lastError.value = err
                    }
                    commandsJson.value = json
                    if (json != "{}") {
                        online.value = true
                    }
                }
                return true
            }
            "config" -> {
                val which = np.getString("which")
                val err = np.getString("error")
                val text = np.getString("text")
                val path = np.getString("path")
                onMain {
                    if (err.isNotEmpty()) {
                        lastError.value = err
                    }
                    configTexts[which] = text
                    configPaths[which] = path
                }
                return true
            }
            "ok" -> {
                if (np.getString("action") == "aiClear") {
                    onMain {
                        askMessages.clear()
                        askConsole.clear()
                        assistantBuffer = StringBuilder()
                    }
                }
                return true
            }
            "error" -> {
                val message = np.getString("message")
                onMain {
                    lastError.value = message
                    askBusy.value = false
                    runBusy.value = false
                    dropThinkingPlaceholder()
                }
                return true
            }
            "runStart" -> {
                val cmdline = np.getString("cmdline")
                onMain {
                    runBusy.value = true
                    runOutput.add(JarvisOutputLine("command", cmdline))
                }
                return true
            }
            "runStdout" -> {
                val line = np.getString("line")
                onMain { runOutput.add(JarvisOutputLine("stdout", line)) }
                return true
            }
            "runStderr" -> {
                val line = np.getString("line")
                onMain { runOutput.add(JarvisOutputLine("stderr", line)) }
                return true
            }
            "runExit" -> {
                val code = np.getInt("code", -1)
                onMain {
                    runBusy.value = false
                    runOutput.add(JarvisOutputLine("exit", "exit $code"))
                }
                return true
            }
            "askStart" -> {
                onMain {
                    askBusy.value = true
                    assistantBuffer = StringBuilder()
                    ensureThinkingPlaceholder()
                }
                return true
            }
            "askStdout" -> {
                val line = np.getString("line")
                onMain {
                    assistantBuffer.append(line).append('\n')
                    replaceLiveAssistant(assistantBuffer.toString().trimEnd())
                }
                return true
            }
            "askStderr" -> {
                val line = np.getString("line")
                onMain { askConsole.add(JarvisOutputLine("stderr", line)) }
                return true
            }
            "askExit" -> {
                val code = np.getInt("code", -1)
                onMain {
                    askBusy.value = false
                    finishLiveAssistant()
                    askConsole.add(JarvisOutputLine("exit", "exit $code"))
                }
                return true
            }
            "screenshot" -> {
                val filename = np.getString("filename")
                val data = np.getString("data")
                onMain {
                    val shot = JarvisChatMessage(
                        fromUser = false,
                        text = filename,
                        live = false,
                        imageBase64 = data,
                    )
                    val liveIdx = askMessages.indexOfLast { !it.fromUser && it.live }
                    if (liveIdx >= 0) {
                        askMessages.add(liveIdx, shot)
                    } else {
                        askMessages.add(shot)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun liveAssistantIndex(): Int =
        askMessages.indexOfLast { !it.fromUser && it.live }

    private fun ensureThinkingPlaceholder() {
        if (liveAssistantIndex() >= 0) {
            return
        }
        askMessages.add(
            JarvisChatMessage(fromUser = false, text = "", live = true, thinking = true),
        )
    }

    private fun dropThinkingPlaceholder() {
        val idx = liveAssistantIndex()
        if (idx >= 0 && askMessages[idx].thinking) {
            askMessages.removeAt(idx)
        }
    }

    private fun replaceLiveAssistant(text: String) {
        val idx = liveAssistantIndex()
        if (idx >= 0) {
            askMessages[idx] = JarvisChatMessage(false, text, live = true, thinking = false)
        } else {
            askMessages.add(JarvisChatMessage(false, text, live = true, thinking = false))
        }
    }

    private fun finishLiveAssistant() {
        val idx = liveAssistantIndex()
        if (idx < 0) {
            return
        }
        val text = assistantBuffer.toString().trimEnd()
        if (text.isNotEmpty()) {
            askMessages[idx] = JarvisChatMessage(false, text, live = false, thinking = false)
            maybeSpeak(text)
        } else if (askMessages[idx].thinking) {
            askMessages.removeAt(idx)
        } else {
            askMessages[idx] = askMessages[idx].copy(live = false, thinking = false)
        }
    }

    private fun maybeSpeak(text: String) {
        if (muted.value) {
            return
        }
        JarvisSpeech.ensure(context)
        JarvisSpeech.speak(text)
    }

    fun setMuted(value: Boolean) {
        muted.value = value
        prefs.edit().putBoolean("muted", value).apply()
        if (value) {
            JarvisSpeech.stop()
        }
    }

    fun toggleMute() {
        setMuted(!muted.value)
    }

    private val mutePattern = Pattern.compile(
        "\\b(mute(?:\\s+yourself)?|be quiet|silence(?:\\s+yourself)?|stop (?:talking|speaking)|hush|voice off)\\b",
        Pattern.CASE_INSENSITIVE,
    )
    private val unmutePattern = Pattern.compile(
        "\\b(unmute|speak again|you (?:may|can) talk|voice on|unmute yourself)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private fun applyVoiceCommand(text: String) {
        if (mutePattern.matcher(text).find()) {
            setMuted(true)
        } else if (unmutePattern.matcher(text).find()) {
            setMuted(false)
            maybeSpeak("Online, sir.")
        }
    }

    fun parsedCommands(): List<JarvisCommand> {
        val out = mutableListOf<JarvisCommand>()
        try {
            val obj = JSONObject(commandsJson.value)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                out.add(JarvisCommand(name, obj.getJSONObject(name)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse commands", e)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun command(name: String): JarvisCommand? = parsedCommands().find { it.name == name }

    fun requestStatus() {
        sendAction("requestStatus")
    }

    fun createCommand(name: String, spec: JSONObject) {
        sendAction("createCommand") {
            it["name"] = name
            it["specJson"] = spec.toString()
        }
    }

    fun updateCommand(name: String, newName: String, spec: JSONObject) {
        sendAction("updateCommand") {
            it["name"] = name
            it["newName"] = newName
            it["specJson"] = spec.toString()
        }
    }

    fun deleteCommand(name: String) {
        sendAction("deleteCommand") { it["name"] = name }
    }

    fun getConfig(which: String) {
        sendAction("getConfig") { it["which"] = which }
    }

    fun setConfig(which: String, text: String) {
        sendAction("setConfig") {
            it["which"] = which
            it["text"] = text
        }
    }

    fun runSegments(segments: List<JarvisSequenceItem>) {
        val id = jobId.intValue++
        val arr = JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("name", seg.name)
            obj.put("mode", seg.mode)
            val flags = JSONObject()
            for ((k, v) in seg.flags) {
                flags.put(k, v)
            }
            obj.put("flags", flags)
            arr.put(obj)
        }
        sendAction("run") {
            it["id"] = id
            it["segmentsJson"] = arr.toString()
        }
    }

    fun runCommand(name: String, flags: Map<String, String>) {
        runSegments(listOf(JarvisSequenceItem(name, flags, "then")))
    }

    fun ask(text: String) {
        applyVoiceCommand(text)
        askMessages.add(JarvisChatMessage(true, text))
        ensureThinkingPlaceholder()
        askBusy.value = true
        val id = jobId.intValue++
        sendAction("ask") {
            it["id"] = id
            it["text"] = text
        }
    }

    fun cancel(kind: String = "") {
        sendAction("cancel") {
            if (kind.isNotEmpty()) {
                it["kind"] = kind
            }
        }
    }

    fun aiClear() {
        sendAction("aiClear")
    }

    fun addToSequence(name: String, flags: Map<String, String>, mode: String = "then") {
        sequence.add(JarvisSequenceItem(name, flags, mode))
    }

    fun toggleSequenceMode(index: Int) {
        if (index in 1 until sequence.size) {
            val item = sequence[index]
            sequence[index] = item.copy(mode = if (item.mode == "and") "then" else "and")
        }
    }

    private fun sendAction(action: String, extra: (NetworkPacket) -> Unit = {}) {
        val np = NetworkPacket(PACKET_TYPE_REQUEST)
        np["action"] = action
        extra(np)
        device.sendPacket(np)
    }

    override val supportedPacketTypes = arrayOf(PACKET_TYPE)
    override val outgoingPacketTypes = arrayOf(PACKET_TYPE_REQUEST)

    companion object {
        const val PACKET_TYPE = "kdeconnect.jarvis"
        const val PACKET_TYPE_REQUEST = "kdeconnect.jarvis.request"
        private const val TAG = "JarvisPlugin"
    }
}

data class JarvisCommand(val name: String, val spec: JSONObject) {
    val description: String
        get() = spec.optString("description")

    fun vars(): List<JarvisVar> {
        val vars = spec.optJSONObject("vars") ?: return emptyList()
        val out = mutableListOf<JarvisVar>()
        val keys = vars.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val raw = vars.opt(key)
            if (raw is JSONObject) {
                out.add(
                    JarvisVar(
                        key,
                        raw.optString("default"),
                        raw.optString("description"),
                        raw.has("default"),
                    ),
                )
            } else {
                out.add(JarvisVar(key, "", "", false))
            }
        }
        return out
    }

    fun stepsPreview(): List<String> {
        val run = spec.opt("run") ?: return emptyList()
        val list = if (run is JSONArray) {
            (0 until run.length()).map { run.get(it) }
        } else {
            listOf(run)
        }
        return list.map { step ->
            when (step) {
                is JSONObject -> step.optString("run")
                else -> step.toString()
            }
        }
    }
}

data class JarvisVar(
    val name: String,
    val default: String,
    val description: String,
    val hasDefault: Boolean,
)

data class JarvisOutputLine(val kind: String, val text: String)

data class JarvisChatMessage(
    val fromUser: Boolean,
    val text: String,
    val live: Boolean = false,
    val imageBase64: String? = null,
    val thinking: Boolean = false,
)

data class JarvisSequenceItem(
    val name: String,
    val flags: Map<String, String>,
    val mode: String,
)
