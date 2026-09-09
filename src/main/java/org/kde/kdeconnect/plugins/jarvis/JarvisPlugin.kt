/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

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
    // Generic, auto-discovered config files (like the web UI's Settings
    // modal — see server.js's GET /api/config/list / KNOWN_CONFIGS): no
    // hardcoded tab list here either, whatever *.json files exist in the
    // jarvis config dir on the desktop show up as a tab.
    val configFiles = mutableStateListOf<JarvisConfigFile>()
    val busy = mutableStateOf(false)
    val sequence = mutableStateListOf<JarvisSequenceItem>()
    // Global capacity mode (mirrors the web UI's topbar #btn-mode-switch —
    // NOT the debug dashboard's local-only override). Pushed proactively by
    // the desktop plugin on every connect/requestStatus (see sendMode() in
    // jarvisplugin.cpp) and again whenever this device calls setMode().
    val capacityMode = mutableStateOf("")
    val capacityModeLabel = mutableStateOf("")
    val capacityModeOptions = mutableStateListOf<JarvisModeOption>()
    private val jobId = mutableIntStateOf(1)
    // Raw, not-yet-split lines of the assistant's reply for the turn
    // currently streaming in (one entry per askStdout packet). Re-split on
    // every new line via splitConsoleDump() below, mirroring
    // web/public/app.js's state.askReplyLines/rerenderAskPendingBubble so
    // the phone and browser clients separate console/tool-trace dump lines
    // from Jarvis's actual reply the same way.
    private var assistantReplyLines = mutableListOf<String>()
    // Whether this ask-turn's console bubble (if any) has already been
    // created in askMessages — reset per turn so each new ask gets its own
    // fresh bubble instead of appending onto a finished previous one,
    // mirroring the web app's state.askTraceBubble being reset to null in
    // finalizeAskBubble.
    private var consoleBubbleShown = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
            "mode" -> {
                // Real global capacity mode (see sendMode()/handleSetMode()
                // in jarvisplugin.cpp) — distinct from any per-call debug
                // override; there's no such override reachable from here.
                val err = np.getString("error")
                val mode = np.getString("mode")
                val options = parseModeOptions(np.getString("optionsJson"))
                onMain {
                    if (err.isNotEmpty()) {
                        lastError.value = err
                    }
                    if (options.isNotEmpty()) {
                        capacityModeOptions.clear()
                        capacityModeOptions.addAll(options)
                    }
                    if (mode.isNotEmpty()) {
                        capacityMode.value = mode
                        capacityModeLabel.value = capacityModeOptions.find { it.mode == mode }?.label ?: mode
                    }
                }
                return true
            }
            "configList" -> {
                val err = np.getString("error")
                val json = np.getString("filesJson").ifEmpty { "[]" }
                val files = mutableListOf<JarvisConfigFile>()
                var parsedOk = true
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        files.add(
                            JarvisConfigFile(
                                name = obj.optString("name"),
                                label = obj.optString("label").ifEmpty { obj.optString("name") },
                                hint = obj.optString("hint"),
                                path = obj.optString("path"),
                            ),
                        )
                    }
                } catch (_: Exception) {
                    // Malformed list from the desktop — leave configFiles as-is.
                    parsedOk = false
                }
                onMain {
                    if (err.isNotEmpty()) {
                        lastError.value = err
                    }
                    if (parsedOk) {
                        configFiles.clear()
                        configFiles.addAll(files)
                    }
                }
                return true
            }
            "ok" -> {
                if (np.getString("action") == "aiClear") {
                    onMain {
                        askMessages.clear()
                        askConsole.clear()
                        assistantReplyLines = mutableListOf()
                        consoleBubbleShown = false
                    }
                }
                return true
            }
            "error" -> {
                val message = np.getString("message")
                onMain {
                    lastError.value = message
                    busy.value = false
                    dropThinkingPlaceholder()
                }
                return true
            }
            "runStart" -> {
                val cmdline = np.getString("cmdline")
                onMain {
                    busy.value = true
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
                    busy.value = false
                    runOutput.add(JarvisOutputLine("exit", "exit $code"))
                }
                return true
            }
            "askStart" -> {
                onMain {
                    busy.value = true
                    assistantReplyLines = mutableListOf()
                    consoleBubbleShown = false
                    ensureThinkingPlaceholder()
                }
                return true
            }
            "askStdout" -> {
                val line = np.getString("line")
                onMain {
                    assistantReplyLines.add(line)
                    val split = splitConsoleDump(assistantReplyLines)
                    updateConsoleBubble(split.dump)
                    replaceLiveAssistant(split.reply.joinToString("\n").trimEnd())
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
                    busy.value = false
                    finishLiveAssistant()
                    askConsole.add(JarvisOutputLine("exit", "exit $code"))
                }
                return true
            }
            "askConfirmRequest" -> {
                // A tool flagged confirm_required (see jarvis-cli's
                // tool_safety.py) has paused the running ask. Shown as its
                // own bubble, never merged into the assistant's live text,
                // so Yes/No is always unambiguous even mid-stream.
                val tool = np.getString("tool")
                val argumentsJson = np.getString("argumentsJson").ifEmpty { "{}" }
                val riskProvider = np.getStringOrNull("riskProvider")
                val riskNote = np.getStringOrNull("riskNote")
                onMain {
                    dropThinkingPlaceholder()
                    askMessages.add(
                        JarvisChatMessage(
                            fromUser = false,
                            text = "",
                            isConfirm = true,
                            confirmTool = tool,
                            confirmArgsJson = argumentsJson,
                            confirmRiskProvider = riskProvider,
                            confirmRiskNote = riskNote,
                        ),
                    )
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
            "askFileActions" -> {
                // Paths the desktop plugin spotted (and verified exist) in
                // the reply that just finished streaming — see
                // jarvisplugin.cpp's sendCollectedFileActions. Shown as its
                // own bubble, inserted right before the live/pending
                // assistant bubble the same way updateConsoleBubble does,
                // so it reads as "attached to" that reply.
                val pathsJson = np.getString("pathsJson")
                if (parseFileActionEntries(pathsJson).isNotEmpty()) {
                    onMain {
                        val bubble = JarvisChatMessage(
                            fromUser = false,
                            text = "",
                            isFileActions = true,
                            fileActionsJson = pathsJson,
                        )
                        val insertAt = liveAssistantIndex()
                        if (insertAt >= 0) {
                            askMessages.add(insertAt, bubble)
                        } else {
                            askMessages.add(bubble)
                        }
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
        val split = splitConsoleDump(assistantReplyLines)
        updateConsoleBubble(split.dump)
        // If the model's entire "reply" somehow turned out to be dump lines,
        // fall back to showing everything rather than leaving the bubble
        // blank (mirrors app.js's finalizeAskBubble).
        val replyLines = split.reply.ifEmpty { assistantReplyLines }
        val text = replyLines.joinToString("\n").trimEnd()
        if (text.isNotEmpty()) {
            askMessages[idx] = JarvisChatMessage(false, text, live = false, thinking = false)
        } else if (askMessages[idx].thinking) {
            askMessages.removeAt(idx)
        } else {
            askMessages[idx] = askMessages[idx].copy(live = false, thinking = false)
        }
    }

    // Creates (once per turn) or updates the dedicated console/tool-trace
    // bubble in askMessages, inserted right before the live/pending
    // assistant bubble — mirrors the web app's ensureAskTraceBubble /
    // renderAskTrace, which keeps a single console bubble per ask-turn that
    // gets its text replaced wholesale on every re-split rather than
    // appended to line by line.
    private fun updateConsoleBubble(dump: List<String>) {
        if (dump.isEmpty()) {
            return
        }
        val text = dump.joinToString("\n")
        val existingIdx = if (consoleBubbleShown) askMessages.indexOfLast { it.isConsole } else -1
        if (existingIdx >= 0) {
            askMessages[existingIdx] = askMessages[existingIdx].copy(text = text)
            return
        }
        val bubble = JarvisChatMessage(fromUser = false, text = text, isConsole = true)
        val insertAt = liveAssistantIndex()
        if (insertAt >= 0) {
            askMessages.add(insertAt, bubble)
        } else {
            askMessages.add(bubble)
        }
        consoleBubbleShown = true
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

    fun getConfigList() {
        sendAction("getConfigList")
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
        askMessages.add(JarvisChatMessage(true, text))
        ensureThinkingPlaceholder()
        busy.value = true
        val id = jobId.intValue++
        sendAction("ask") {
            it["id"] = id
            it["text"] = text
        }
    }

    fun cancel() {
        sendAction("cancel")
    }

    fun respondToConfirm(message: JarvisChatMessage, approved: Boolean) {
        val idx = askMessages.indexOf(message)
        if (idx < 0) return
        askMessages[idx] = message.copy(confirmResolved = true, confirmApproved = approved)
        // Either way jarvis keeps talking (declining still gets a reply
        // acknowledging it), so bring the thinking placeholder straight
        // back — same as right after sending a normal ask.
        ensureThinkingPlaceholder()
        sendAction("askConfirmResponse") { it["approved"] = approved }
    }

    // Reveal in Explorer / Open location / Open file for one of the paths
    // in an askFileActions bubble — kind is "reveal" | "openLocation" |
    // "openFile", matching jarvisplugin.cpp's handleFileAction. Fire-and-
    // forget from the UI's point of view: success/failure comes back as a
    // plain "ok"/"error" packet, surfaced via lastError like any other
    // action here rather than needing its own round-trip tracking.
    fun fileAction(path: String, kind: String) {
        sendAction("fileAction") {
            it["path"] = path
            it["fileAction"] = kind
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

// One entry from an "askFileActions" packet's pathsJson (see
// jarvisplugin.cpp's sendCollectedFileActions) — a path the desktop plugin
// found in Jarvis's reply and already confirmed exists on that PC.
data class JarvisFileActionEntry(val path: String, val isFolder: Boolean)

fun parseFileActionEntries(json: String?): List<JarvisFileActionEntry> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            JarvisFileActionEntry(obj.optString("path"), obj.optBoolean("isFolder", false))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

// One entry from GET /api/config/list, relayed by the desktop plugin as
// "configList" — mirrors app.js's settingsFiles: whatever *.json files
// live in the jarvis config dir, auto-discovered rather than hardcoded.
data class JarvisConfigFile(val name: String, val label: String, val hint: String, val path: String)

// Jarvis's own CLI output is plain text like "J.A.R.V.I.S: <reply>" (see
// jarvis-cli's cli.py: handle_ai_prompt). But when the model calls a tool
// like run_command, it sometimes echoes the tool's raw output verbatim as
// the start of its own answer, and only *then* writes its actual signed
// reply. Some providers also echo their own tool-call/tool-result
// scaffolding as plain text (e.g. "[called run_command with {...}]") instead
// of routing it through the real function-calling API (see ai_client.py's
// _TOOL_TRACE_LINE). Either way that content isn't Jarvis "the persona"
// talking and shouldn't be glued into the same chat bubble as the real
// reply — it belongs in the Console view instead.
//
// This is a straight port of web/public/app.js's splitConsoleDump, kept in
// sync so the phone and browser clients split the same way.
private val NAME_PREFIX_LINE = Regex("^([^\\n:]{1,40}):\\s(.*)$")
private val INLINE_TOOL_TRACE_LINE = Regex("^\\[(called\\s|tool result\\b)", RegexOption.IGNORE_CASE)

data class JarvisConsoleSplit(val name: String?, val dump: List<String>, val reply: List<String>)

private fun splitConsoleDump(lines: List<String>): JarvisConsoleSplit {
    var splitAt = -1
    var name: String? = null
    var firstReplyLine: String? = null
    for (i in lines.indices) {
        val m = NAME_PREFIX_LINE.find(lines[i])
        if (m != null) {
            splitAt = i
            name = m.groupValues[1]
            firstReplyLine = m.groupValues[2]
            break
        }
    }

    val dump = mutableListOf<String>()
    val candidateReply: List<String> = if (splitAt == -1) {
        lines.toList()
    } else {
        dump.addAll(lines.subList(0, splitAt))
        listOf(firstReplyLine.orEmpty()) + lines.subList(splitAt + 1, lines.size)
    }

    val reply = mutableListOf<String>()
    for (line in candidateReply) {
        if (INLINE_TOOL_TRACE_LINE.containsMatchIn(line.trim())) dump.add(line.trim()) else reply.add(line)
    }
    return JarvisConsoleSplit(name, dump, reply)
}

data class JarvisChatMessage(
    val fromUser: Boolean,
    val text: String,
    val live: Boolean = false,
    val imageBase64: String? = null,
    val thinking: Boolean = false,
    val isConfirm: Boolean = false,
    val confirmTool: String? = null,
    val confirmArgsJson: String? = null,
    val confirmRiskProvider: String? = null,
    val confirmRiskNote: String? = null,
    val confirmResolved: Boolean = false,
    val confirmApproved: Boolean = false,
    // Console/tool-trace dump lines split off the reply by splitConsoleDump,
    // shown as their own bubble in the thread — mirrors the web app's
    // ask-msg--console bubble (ensureAskTraceBubble/renderAskTrace).
    val isConsole: Boolean = false,
    // Reveal in Explorer / Open location / Open file buttons for paths the
    // desktop plugin spotted (and verified exist) in this reply's text —
    // see JarvisPlugin.onPacketReceived's "askFileActions" case and
    // jarvisplugin.cpp's collectFileActionCandidates/sendCollectedFileActions.
    val isFileActions: Boolean = false,
    val fileActionsJson: String? = null,
)

data class JarvisSequenceItem(
    val name: String,
    val flags: Map<String, String>,
    val mode: String,
)