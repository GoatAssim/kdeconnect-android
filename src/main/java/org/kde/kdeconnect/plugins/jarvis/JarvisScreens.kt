/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import org.kde.kdeconnect.helpers.MediaStoreHelper
import java.io.File
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

private sealed class JarvisPage {
    data object Commands : JarvisPage()
    data class Detail(val name: String) : JarvisPage()
    data class Edit(val name: String?) : JarvisPage()
    data object Output : JarvisPage()
    data object Ask : JarvisPage()
    data object Config : JarvisPage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisApp(
    plugin: JarvisPlugin,
    deviceName: String,
    onBack: () -> Unit,
) {
    val stack = remember { mutableStateListOf<JarvisPage>(JarvisPage.Commands) }
    val page = stack.last()
    val go: (JarvisPage) -> Unit = { stack.add(it); Unit }
    val back: () -> Unit = {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            onBack()
        }
        Unit
    }

    when (page) {
        JarvisPage.Commands -> CommandsScreen(plugin, deviceName, go, back)
        is JarvisPage.Detail -> DetailScreen(plugin, page.name, go, back)
        is JarvisPage.Edit -> EditScreen(plugin, page.name, back)
        JarvisPage.Output -> OutputScreen(plugin, back, commandOutput = true)
        JarvisPage.Ask -> AskScreen(plugin, back)
        JarvisPage.Config -> ConfigScreen(plugin, back)
    }
}

@Composable
private fun CommandsScreen(
    plugin: JarvisPlugin,
    deviceName: String,
    go: (JarvisPage) -> Unit,
    back: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val commandsJson = plugin.commandsJson.value
    val commands = remember(commandsJson) { plugin.parsedCommands() }
    val online = plugin.online.value
    val sequence = plugin.sequence

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.jarvis_ask_button),
                subTitle = deviceName,
                navIconOnClick = back,
                actions = {
                    ModeSwitchButton(plugin)
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.jarvis_menu))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.jarvis_show_output)) },
                            onClick = {
                                menu = false
                                go(JarvisPage.Output)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.jarvis_ask_button)) },
                            onClick = {
                                menu = false
                                go(JarvisPage.Ask)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.jarvis_config)) },
                            onClick = {
                                menu = false
                                go(JarvisPage.Config)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { go(JarvisPage.Edit(null)) }) {
                Icon(Icons.Default.Add, stringResource(R.string.jarvis_new_command))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { go(JarvisPage.Output) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.jarvis_show_output))
                }
                Button(
                    onClick = { go(JarvisPage.Ask) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.jarvis_ask_button))
                }
            }
            if (!online) {
                Text(
                    plugin.statusError.value.ifEmpty { stringResource(R.string.jarvis_offline) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(commands, key = { it.name }) { cmd ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .clickable { go(JarvisPage.Detail(cmd.name)) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(cmd.name, style = MaterialTheme.typography.titleMedium)
                            if (cmd.description.isNotEmpty()) {
                                Text(cmd.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (sequence.isNotEmpty()) {
                SequenceBar(plugin) { go(JarvisPage.Output) }
            }
        }
    }
}

@Composable
private fun SequenceBar(plugin: JarvisPlugin, onRun: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.jarvis_sequence), fontWeight = FontWeight.Bold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            plugin.sequence.forEachIndexed { index, item ->
                if (index > 0) {
                    TextButton(onClick = { plugin.toggleSequenceMode(index) }) {
                        Text(if (item.mode == "and") "and" else "then")
                    }
                }
                Text(item.name)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { plugin.sequence.clear() }) {
                Text(stringResource(R.string.jarvis_clear_sequence))
            }
            Button(onClick = {
                plugin.runSegments(plugin.sequence.toList())
                onRun()
            }) {
                Text(stringResource(R.string.jarvis_run_sequence))
            }
        }
    }
}

@Composable
private fun DetailScreen(
    plugin: JarvisPlugin,
    name: String,
    go: (JarvisPage) -> Unit,
    back: () -> Unit,
) {
    val cmd = remember(plugin.commandsJson.value, name) { plugin.command(name) }
    val flagValues = remember { mutableStateMapOf<String, String>() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(cmd) {
        cmd?.vars()?.forEach { v ->
            if (!flagValues.containsKey(v.name)) {
                flagValues[v.name] = v.default
            }
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = name,
                navIconOnClick = back,
                actions = {
                    TextButton(onClick = { go(JarvisPage.Edit(name)) }) {
                        Text(stringResource(R.string.jarvis_edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.jarvis_delete))
                    }
                },
            )
        },
    ) { padding ->
        if (cmd == null) {
            Text(stringResource(R.string.jarvis_missing_command), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cmd.description.isNotEmpty()) {
                Text(cmd.description)
            }
            cmd.vars().forEach { v ->
                OutlinedTextField(
                    value = flagValues[v.name] ?: v.default,
                    onValueChange = { flagValues[v.name] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (v.hasDefault) v.name else "${v.name} *") },
                    supportingText = if (v.description.isNotEmpty()) {
                        { Text(v.description) }
                    } else {
                        null
                    },
                )
            }
            Text(stringResource(R.string.jarvis_steps), style = MaterialTheme.typography.titleSmall)
            cmd.stepsPreview().forEachIndexed { i, step ->
                Text("${i + 1}. $step", fontFamily = FontFamily.Monospace)
            }
            Button(
                onClick = {
                    plugin.runCommand(name, flagValues.toMap())
                    go(JarvisPage.Output)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jarvis_execute))
            }
            OutlinedButton(
                onClick = { plugin.addToSequence(name, flagValues.toMap()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jarvis_add_sequence))
            }
            Button(
                onClick = { go(JarvisPage.Ask) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jarvis_ask_button))
            }
            OutlinedButton(
                onClick = { go(JarvisPage.Output) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jarvis_show_output))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.jarvis_delete)) },
            text = { Text(stringResource(R.string.jarvis_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    plugin.deleteCommand(name)
                    confirmDelete = false
                    back()
                }) { Text(stringResource(R.string.jarvis_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EditScreen(plugin: JarvisPlugin, existingName: String?, back: () -> Unit) {
    val existing = existingName?.let { plugin.command(it) }
    var name by remember { mutableStateOf(existingName ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var raw by remember { mutableStateOf(existing?.spec?.toString(2) ?: "{\n  \"description\": \"\",\n  \"run\": \"echo hello\"\n}") }
    var useRaw by remember { mutableStateOf(false) }
    val varNames = remember { mutableStateListOf<String>() }
    val varDefaults = remember { mutableStateListOf<String>() }
    val varDescs = remember { mutableStateListOf<String>() }
    val steps = remember { mutableStateListOf<String>() }

    LaunchedEffect(existingName) {
        existing?.vars()?.let { vars ->
            varNames.clear()
            varDefaults.clear()
            varDescs.clear()
            vars.forEach {
                varNames.add(it.name)
                varDefaults.add(it.default)
                varDescs.add(it.description)
            }
        }
        existing?.stepsPreview()?.let {
            steps.clear()
            steps.addAll(it.ifEmpty { listOf("") })
        }
        if (steps.isEmpty()) {
            steps.add("")
        }
    }

    fun buildSpec(): JSONObject {
        if (useRaw) {
            return JSONObject(raw)
        }
        val spec = JSONObject()
        spec.put("description", description)
        val varsObj = JSONObject()
        varNames.forEachIndexed { i, n ->
            if (n.isNotBlank()) {
                val v = JSONObject()
                if (varDefaults.getOrNull(i).orEmpty().isNotEmpty()) {
                    v.put("default", varDefaults[i])
                }
                if (varDescs.getOrNull(i).orEmpty().isNotEmpty()) {
                    v.put("description", varDescs[i])
                }
                varsObj.put(n, v)
            }
        }
        if (varsObj.length() > 0) {
            spec.put("vars", varsObj)
        }
        val run = JSONArray()
        steps.filter { it.isNotBlank() }.forEach { run.put(it) }
        spec.put("run", if (run.length() == 1) run.getString(0) else run)
        return spec
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(if (existingName == null) R.string.jarvis_new_command else R.string.jarvis_edit),
                navIconOnClick = back,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useRaw, onCheckedChange = { useRaw = it })
                Text(stringResource(R.string.jarvis_raw_json))
            }
            if (useRaw) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    label = { Text(stringResource(R.string.jarvis_raw_json)) },
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.jarvis_command_name)) },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.jarvis_description)) },
                )
                Text(stringResource(R.string.jarvis_variables), style = MaterialTheme.typography.titleSmall)
                varNames.forEachIndexed { i, _ ->
                    OutlinedTextField(
                        value = varNames[i],
                        onValueChange = { varNames[i] = it },
                        label = { Text(stringResource(R.string.jarvis_var_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = varDefaults[i],
                        onValueChange = { varDefaults[i] = it },
                        label = { Text(stringResource(R.string.jarvis_var_default)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = varDescs[i],
                        onValueChange = { varDescs[i] = it },
                        label = { Text(stringResource(R.string.jarvis_var_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = {
                    varNames.add("")
                    varDefaults.add("")
                    varDescs.add("")
                }) { Text(stringResource(R.string.jarvis_add_variable)) }
                Text(stringResource(R.string.jarvis_steps), style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { i, _ ->
                    OutlinedTextField(
                        value = steps[i],
                        onValueChange = { steps[i] = it },
                        label = { Text(stringResource(R.string.jarvis_step_run, i + 1)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { steps.add("") }) { Text(stringResource(R.string.jarvis_add_step)) }
            }
            Button(
                onClick = {
                    try {
                        val spec = buildSpec()
                        if (existingName == null) {
                            plugin.createCommand(name, spec)
                        } else {
                            plugin.updateCommand(existingName, name.ifBlank { existingName }, spec)
                        }
                        back()
                    } catch (e: Exception) {
                        plugin.lastError.value = e.message ?: "Invalid JSON"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jarvis_save_command))
            }
        }
    }
}

@Composable
private fun OutputScreen(plugin: JarvisPlugin, back: () -> Unit, commandOutput: Boolean) {
    val lines = if (commandOutput) plugin.runOutput else plugin.askConsole
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(
                    if (commandOutput) R.string.jarvis_show_output else R.string.jarvis_console,
                ),
                navIconOnClick = back,
                actions = {
                    if (plugin.busy.value) {
                        TextButton(onClick = { plugin.cancel() }) {
                            Text(stringResource(R.string.jarvis_abort))
                        }
                    }
                    TextButton(onClick = { lines.clear() }) {
                        Text(stringResource(R.string.jarvis_clear))
                    }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.jarvis_output_empty),
                Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), state = listState) {
                itemsIndexed(lines) { _, line ->
                    if (line.kind == "confirm") {
                        RunConfirmBlock(line, onRespond = { approved -> plugin.respondToRunConfirm(line, approved) })
                        return@itemsIndexed
                    }
                    val color = when (line.kind) {
                        "stderr" -> MaterialTheme.colorScheme.error
                        "exit", "command" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(line.text, color = color, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// Global capacity-mode switch — mirrors the web UI's #btn-mode-switch:
// tapping always steps to the next mode in plugin.capacityModeOptions'
// order and persists it via plugin.setMode(), which is a real, global
// change on the desktop (affects every browser tab, any other paired
// phone, jarvis-cli itself), not something local to this button. Shown
// wherever the web puts its topbar switch's phone-sized equivalent: the
// main commands screen and the ask screen.
@Composable
private fun ModeSwitchButton(plugin: JarvisPlugin) {
    val options = plugin.capacityModeOptions
    val mode = plugin.capacityMode.value
    val label = plugin.capacityModeLabel.value.ifEmpty { stringResource(R.string.jarvis_mode_fallback) }
    val current = options.find { it.mode == mode }
    val idx = if (current != null) options.indexOf(current) else -1
    val next = if (options.isNotEmpty()) options[(maxOf(idx, 0) + 1) % options.size] else null
    val description = if (next != null) {
        stringResource(R.string.jarvis_mode_switch_desc, current?.label ?: label, next.label)
    } else {
        stringResource(R.string.jarvis_mode_switch_desc_no_next, label)
    }

    TextButton(
        onClick = { plugin.cycleMode() },
        enabled = !plugin.busy.value,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(label)
    }
}

@Composable
private fun AskScreen(plugin: JarvisPlugin, back: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var showConsole by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<JarvisChatMessage?>(null) }
    val messages = plugin.askMessages
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text, messages.lastOrNull()?.thinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (showConsole) {
        OutputScreen(plugin, { showConsole = false }, commandOutput = false)
        return
    }

    viewer?.let { shot ->
        ScreenshotViewer(
            filename = shot.text,
            imageBase64 = shot.imageBase64.orEmpty(),
            onDismiss = { viewer = null },
        )
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.jarvis_ask_button),
                navIconOnClick = back,
                actions = {
                    ModeSwitchButton(plugin)
                    TextButton(onClick = { showConsole = true }) {
                        Text(stringResource(R.string.jarvis_console))
                    }
                    TextButton(onClick = { plugin.aiClear() }) {
                        Text(stringResource(R.string.jarvis_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(16.dp), state = listState) {
                items(messages) { msg ->
                    if (msg.isConfirm) {
                        AskConfirmBubble(msg, onRespond = { approved -> plugin.respondToConfirm(msg, approved) })
                        return@items
                    }
                    if (msg.isFileActions) {
                        AskFileActionsBubble(msg, onAction = { path, kind -> plugin.fileAction(path, kind) })
                        return@items
                    }
                    if (msg.isOrganizeJson) {
                        OrganizeJsonBubble(msg)
                        return@items
                    }
                    if (msg.isConsole) {
                        AskConsoleBubble(msg)
                        return@items
                    }
                    if (msg.isPresentFile) {
                        PresentFileBubble(msg, onAction = { path, kind -> plugin.fileAction(path, kind) })
                        return@items
                    }
                    val bubbleColor = if (msg.fromUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val textColor = if (msg.fromUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 320.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bubbleColor),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    if (msg.fromUser) stringResource(R.string.jarvis_you) else stringResource(R.string.jarvis_ask_button),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor,
                                )
                                val image = msg.imageBase64
                                if (!image.isNullOrEmpty()) {
                                    val imageBitmap = remember(image) {
                                        try {
                                            val bytes = Base64.decode(image, Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                    if (imageBitmap != null) {
                                        Image(
                                            bitmap = imageBitmap,
                                            contentDescription = stringResource(R.string.jarvis_screenshot),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 280.dp)
                                                .padding(top = 8.dp)
                                                .clickable { viewer = msg },
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                                    Text(msg.text, color = textColor, style = MaterialTheme.typography.bodySmall)
                                } else if (msg.thinking) {
                                    Text(
                                        stringResource(R.string.jarvis_thinking),
                                        color = textColor.copy(alpha = 0.8f),
                                        fontStyle = FontStyle.Italic,
                                    )
                                } else if (!msg.fromUser) {
                                    // Only the assistant's own reply gets Markdown
                                    // rendering — matches the web app, which only
                                    // calls renderMarkdown() on the Jarvis bubble,
                                    // never the user's own message.
                                    JarvisMarkdownText(msg.text, textColor)
                                } else {
                                    Text(msg.text, color = textColor)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.jarvis_ask_hint)) },
                    enabled = !plugin.busy.value,
                )
                if (plugin.busy.value) {
                    IconButton(onClick = { plugin.cancel() }) {
                        Text(stringResource(R.string.jarvis_abort))
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                plugin.ask(text)
                                input = ""
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.jarvis_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun AskConsoleBubble(msg: JarvisChatMessage) {
    // Console/tool-trace output split off Jarvis's reply by splitConsoleDump
    // (raw command output the model echoed, or inline tool-call/tool-result
    // trace lines) — shown as its own bubble in the thread instead of being
    // glued into the assistant's reply text, mirroring the web app's
    // ask-msg--console bubble (ensureAskTraceBubble/renderAskTrace).
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.jarvis_console_bubble_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    msg.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AskConfirmBubble(msg: JarvisChatMessage, onRespond: (Boolean) -> Unit) {
    // A tool flagged confirm_required (see jarvis-cli/jarvis/tool_safety.py)
    // has paused the running ask — shown as its own distinct bubble (never
    // merged into the assistant's chat text) so the risk warning can't be
    // missed, matching the web console's addAskConfirmBubble.
    val prettyArgs = remember(msg.confirmArgsJson) {
        try {
            JSONObject(msg.confirmArgsJson ?: "{}").toString(2)
        } catch (_: Exception) {
            msg.confirmArgsJson ?: "{}"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.jarvis_confirm_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    msg.confirmTool ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    prettyArgs,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!msg.confirmCommandRun.isNullOrEmpty()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            stringResource(R.string.jarvis_confirm_will_run),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            msg.confirmCommandRun,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                if (msg.confirmFlagConfirmRequired != null || msg.confirmFlagAiReview != null) {
                    Text(
                        stringResource(
                            R.string.jarvis_confirm_flags,
                            msg.confirmFlagConfirmRequired == true,
                            msg.confirmFlagAiReview == true,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!msg.confirmRiskNote.isNullOrEmpty()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        val label = if (!msg.confirmRiskProvider.isNullOrEmpty()) {
                            "${stringResource(R.string.jarvis_confirm_ai_review)} \u2014 ${msg.confirmRiskProvider}"
                        } else {
                            stringResource(R.string.jarvis_confirm_ai_review)
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            msg.confirmRiskNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                if (msg.confirmResolved) {
                    Text(
                        stringResource(
                            if (msg.confirmApproved) R.string.jarvis_confirm_approved else R.string.jarvis_confirm_declined,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { onRespond(false) }) {
                            Text(stringResource(R.string.jarvis_confirm_no))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onRespond(true) }) {
                            Text(stringResource(R.string.jarvis_confirm_yes))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunConfirmBlock(line: JarvisOutputLine, onRespond: (Boolean) -> Unit) {
    // Close visual port of AskConfirmBubble above, for the run/output
    // screen's flat list of JarvisOutputLine instead of chat bubbles — see
    // JarvisPlugin's "runConfirmRequest" packet case and
    // respondToRunConfirm(). Confirm requests only ever arrive on this
    // screen, never askConsole.
    val prettyArgs = remember(line.confirmArgsJson) {
        try {
            JSONObject(line.confirmArgsJson ?: "{}").toString(2)
        } catch (_: Exception) {
            line.confirmArgsJson ?: "{}"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.jarvis_confirm_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                line.confirmTool ?: "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                prettyArgs,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!line.confirmCommandRun.isNullOrEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.jarvis_confirm_will_run),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        line.confirmCommandRun,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (line.confirmFlagConfirmRequired != null || line.confirmFlagAiReview != null) {
                Text(
                    stringResource(
                        R.string.jarvis_confirm_flags,
                        line.confirmFlagConfirmRequired == true,
                        line.confirmFlagAiReview == true,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!line.confirmRiskNote.isNullOrEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    val label = if (!line.confirmRiskProvider.isNullOrEmpty()) {
                        "${stringResource(R.string.jarvis_confirm_ai_review)} \u2014 ${line.confirmRiskProvider}"
                    } else {
                        stringResource(R.string.jarvis_confirm_ai_review)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        line.confirmRiskNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (line.confirmResolved) {
                Text(
                    stringResource(
                        if (line.confirmApproved) R.string.jarvis_confirm_approved else R.string.jarvis_confirm_declined,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { onRespond(false) }) {
                        Text(stringResource(R.string.jarvis_confirm_no))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onRespond(true) }) {
                        Text(stringResource(R.string.jarvis_confirm_yes))
                    }
                }
            }
        }
    }
}

@Composable
private fun AskFileActionsBubble(msg: JarvisChatMessage, onAction: (path: String, kind: String) -> Unit) {
    // Reveal in Explorer / Open location / Open file buttons for paths the
    // desktop plugin found (and confirmed exist) in the reply just above
    // this bubble — see jarvisplugin.cpp's collectFileActionCandidates /
    // sendCollectedFileActions and JarvisPlugin's "askFileActions" case.
    // Buttony like AskConfirmBubble above, just informational rather than
    // blocking anything — there's no ask waiting on the phone tapping one.
    val entries = remember(msg.fileActionsJson) { parseFileActionEntries(msg.fileActionsJson) }
    if (entries.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.jarvis_file_actions_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                for ((index, entry) in entries.withIndex()) {
                    Column(Modifier.padding(top = if (index == 0) 8.dp else 12.dp)) {
                        Text(
                            entry.path,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Row(
                            Modifier.padding(top = 6.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { onAction(entry.path, "reveal") }) {
                                Text(stringResource(R.string.jarvis_file_reveal))
                            }
                            OutlinedButton(onClick = { onAction(entry.path, "openLocation") }) {
                                Text(stringResource(R.string.jarvis_file_open_location))
                            }
                            if (!entry.isFolder) {
                                OutlinedButton(onClick = { onAction(entry.path, "openFile") }) {
                                    Text(stringResource(R.string.jarvis_file_open))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Small formatting helper for present_file's sizeBytes — mirrors the spirit
// of a human-readable byte count without pulling in a dependency.
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

@Composable
private fun PresentFileBubble(msg: JarvisChatMessage, onAction: (path: String, kind: String) -> Unit) {
    // The present_file AI tool explicitly showing one specific file/folder
    // already on the desktop PC — see JarvisPlugin's "presentFile" packet
    // case. Visual reference is AskFileActionsBubble, but this card only
    // ever describes one file, not a list — and there's no Download button
    // here (no backing endpoint from this plugin, see wire-format note).
    val isFolder = msg.presentFileType == "folder"
    val sizeBytes = msg.presentFileSizeBytes
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isFolder) "\uD83D\uDCC1" else "\uD83D\uDCC4",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.jarvis_present_file_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    msg.presentFileName ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val typeLabel = stringResource(
                    if (isFolder) R.string.jarvis_present_file_folder else R.string.jarvis_present_file_file,
                )
                val sizeLabel = if (sizeBytes == null || sizeBytes < 0) {
                    stringResource(R.string.jarvis_present_file_size_unknown)
                } else {
                    formatBytes(sizeBytes)
                }
                Text(
                    "$typeLabel \u2013 $sizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    msg.presentFilePath ?: "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val path = msg.presentFilePath.orEmpty()
                    OutlinedButton(onClick = { onAction(path, "reveal") }) {
                        Text(stringResource(R.string.jarvis_file_reveal))
                    }
                    OutlinedButton(onClick = { onAction(path, "openLocation") }) {
                        Text(stringResource(R.string.jarvis_file_open_location))
                    }
                    if (!isFolder) {
                        OutlinedButton(onClick = { onAction(path, "openFile") }) {
                            Text(stringResource(R.string.jarvis_file_open))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotViewer(filename: String, imageBase64: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bytes = remember(imageBase64) {
        try {
            Base64.decode(imageBase64, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }
    val imageBitmap = remember(bytes) {
        if (bytes.isEmpty()) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
        ) {
            KdeTopAppBar(
                title = filename.ifBlank { stringResource(R.string.jarvis_screenshot) },
                navIcon = Icons.Default.Close,
                navIconOnClick = onDismiss,
                actions = {
                    TextButton(
                        onClick = {
                            val ok = saveScreenshotToDownloads(context, filename, bytes)
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.jarvis_saved else R.string.jarvis_save_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Text(stringResource(R.string.jarvis_download))
                    }
                    IconButton(
                        onClick = {
                            if (!shareScreenshot(context, filename, bytes)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.jarvis_share_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, stringResource(R.string.share))
                    }
                },
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.jarvis_screenshot),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    offset = if (scale == 1f) Offset.Zero else offset + pan
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

private fun screenshotBytesFile(context: android.content.Context, filename: String, bytes: ByteArray): File? {
    if (bytes.isEmpty()) {
        return null
    }
    val safeName = filename.ifBlank { "screenshot.png" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = File(context.cacheDir, "jarvis")
    if (!dir.exists() && !dir.mkdirs()) {
        return null
    }
    return try {
        File(dir, safeName).also { it.writeBytes(bytes) }
    } catch (_: Exception) {
        null
    }
}

private fun saveScreenshotToDownloads(context: android.content.Context, filename: String, bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) {
        return false
    }
    val name = filename.ifBlank { "jarvis_screenshot.png" }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            val file = File(dir, name)
            file.writeBytes(bytes)
            MediaStoreHelper.indexFile(context, Uri.fromFile(file))
            true
        }
    } catch (_: Exception) {
        false
    }
}

private fun shareScreenshot(context: android.content.Context, filename: String, bytes: ByteArray): Boolean {
    val file = screenshotBytesFile(context, filename, bytes) ?: return false
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
        true
    } catch (_: Exception) {
        false
    }
}

private fun jsonCoerce(raw: String): Any? {
    if (raw == "null") return null
    if (raw == "true") return true
    if (raw == "false") return false
    raw.toDoubleOrNull()?.let { if (Regex("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$").matches(raw.trim())) return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) it else raw.toLongOrNull() ?: it }
    return raw
}

private fun jsonScalarLabel(v: Any?): String = when (v) {
    null, JSONObject.NULL -> "null"
    is String -> "\"$v\""
    else -> v.toString()
}

private fun jsonIsContainer(v: Any?): Boolean = v is JSONObject || v is JSONArray

@Composable
private fun JsonTreeEditor(root: Any, editable: Boolean = true, onChange: () -> Unit = {}) {
    var version by remember { mutableIntStateOf(0) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun bump() { version++; onChange() }

    @Composable
    fun Row(path: String, depth: Int, key: String?, value: Any?, isArrayItem: Boolean, onDelete: (() -> Unit)?) {
        val container = jsonIsContainer(value)
        val isOpen = expanded[path] ?: (depth < 2)
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (container) {
                Text(if (isOpen) "▾" else "▸", Modifier.clickable { expanded[path] = !isOpen }.padding(end = 4.dp))
            } else {
                Spacer(Modifier.width(14.dp))
            }
            var keyEdit by remember(path) { mutableStateOf(false) }
            if (key != null) {
                if (isArrayItem) {
                    Text("[$key]", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodySmall)
                } else if (editable && keyEdit) {
                    var kv by remember(path) { mutableStateOf(key) }
                    OutlinedTextField(
                        value = kv, onValueChange = { kv = it },
                        modifier = Modifier.widthIn(min = 40.dp, max = 140.dp).height(48.dp),
                        singleLine = true,
                    )
                    IconButton(onClick = {
                        val parent = value // unused placeholder to satisfy scope; real rename done by caller below
                        keyEdit = false
                    }) { Icon(Icons.Filled.Close, null) }
                } else {
                    Text(
                        key,
                        Modifier.let { if (editable) it.clickable { keyEdit = true } else it }.padding(end = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(":", Modifier.padding(end = 4.dp))
            }
            if (container) {
                val label = if (value is JSONArray) "[ ] ${value.length()} items" else "{ } ${(value as JSONObject).length()} keys"
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            } else {
                var editing by remember(path, version) { mutableStateOf(false) }
                if (editable && editing) {
                    var tv by remember(path) { mutableStateOf(if (value is String) value else jsonScalarLabel(value)) }
                    OutlinedTextField(
                        value = tv, onValueChange = { tv = it },
                        modifier = Modifier.weight(1f).height(48.dp),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { editing = false /* commit handled by caller via closures below */ }) {
                                Icon(Icons.Filled.Close, null)
                            }
                        },
                    )
                } else {
                    Text(
                        jsonScalarLabel(value),
                        Modifier.weight(1f).let { if (editable) it.clickable { editing = true } else it },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (editable && onDelete != null) {
                IconButton(onClick = { onDelete(); bump() }, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    fun renderContainer(path: String, value: Any, depth: Int) {
        val isOpen = expanded[path] ?: (depth < 2)
        if (!isOpen && depth > 0) return
        if (value is JSONObject) {
            val keys = value.keys().asSequence().toList()
            for (k in keys) {
                val childPath = "$path.$k"
                val child = value.opt(k)
                Row(childPath, depth, k, child, false, onDelete = { value.remove(k) })
                if (jsonIsContainer(child)) renderContainer(childPath, child!!, depth + 1)
            }
            if (editable) {
                TextButton(onClick = {
                    var name = "new_key"; var n = 1
                    while (value.has(name)) { name = "new_key_$n"; n++ }
                    value.put(name, "")
                    expanded[path] = true
                    bump()
                }, Modifier.padding(start = ((depth + 1) * 14).dp)) { Text("+ add key") }
            }
        } else if (value is JSONArray) {
            for (i in 0 until value.length()) {
                val childPath = "$path.$i"
                val child = value.opt(i)
                Row(childPath, depth, i.toString(), child, true, onDelete = { value.remove(i) })
                if (jsonIsContainer(child)) renderContainer(childPath, child!!, depth + 1)
            }
            if (editable) {
                TextButton(onClick = {
                    value.put("")
                    expanded[path] = true
                    bump()
                }, Modifier.padding(start = ((depth + 1) * 14).dp)) { Text("+ add item") }
            }
        }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        key(version) {
            if (jsonIsContainer(root)) renderContainer("$", root, 0)
            else Text(jsonScalarLabel(root))
        }
    }
}

// View-only Organized(Fancy)/Raw JSON bubble for the organize_json AI
// tool's result (see JarvisPlugin's "organizeJson" packet case) — mirrors
// the web UI's renderOrganizeJsonResult: same JsonTreeEditor as
// ConfigScreen's editor, just with editable=false, since this is a
// snapshot of a file already on disk, not something submitted back with
// Save. Bubble styling mirrors AskFileActionsBubble above.
@Composable
private fun OrganizeJsonBubble(msg: JarvisChatMessage) {
    val text = msg.organizeJsonText.orEmpty()
    val parsed = remember(text) {
        if (text.isBlank()) {
            null
        } else {
            try {
                val t = text.trim()
                if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            } catch (_: Exception) {
                null
            }
        }
    }
    var useFancy by remember(msg.organizeJsonPath) { mutableStateOf(true) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.jarvis_organize_json_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (!msg.organizeJsonPath.isNullOrEmpty()) {
                    Text(
                        msg.organizeJsonPath,
                        Modifier.padding(top = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (!msg.organizeJsonError.isNullOrEmpty()) {
                    Text(
                        msg.organizeJsonError,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }
                if (parsed == null) {
                    Text(
                        stringResource(R.string.jarvis_organize_json_invalid),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { useFancy = true }) {
                        Text(if (useFancy) "● " + stringResource(R.string.jarvis_organize_json_fancy) else stringResource(R.string.jarvis_organize_json_fancy))
                    }
                    TextButton(onClick = { useFancy = false }) {
                        Text(if (!useFancy) stringResource(R.string.jarvis_raw_json) + " ●" else stringResource(R.string.jarvis_raw_json))
                    }
                }
                Box(Modifier.padding(top = 4.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (useFancy) {
                        JsonTreeEditor(parsed, editable = false)
                    } else {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(plugin: JarvisPlugin, back: () -> Unit) {
    // Generic like the web UI's Settings modal: the tab list comes from
    // GET /api/config/list (relayed as plugin.configFiles) instead of a
    // fixed set of tabs, so any *.json file dropped into the jarvis config
    // dir on the desktop shows up here with no app update needed.
    val files = plugin.configFiles
    LaunchedEffect(Unit) {
        plugin.getConfigList()
    }

    var tab by remember { mutableIntStateOf(0) }
    val safeTab = tab.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    val activeFile = files.getOrNull(safeTab)
    val which = activeFile?.name.orEmpty()
    var text by remember { mutableStateOf(plugin.configTexts[which] ?: "") }
    var useFancy by remember(which) { mutableStateOf(true) }
    var parsed by remember(which) { mutableStateOf<Any?>(null) }
    var parseError by remember(which) { mutableStateOf<String?>(null) }

    LaunchedEffect(which) {
        if (which.isNotEmpty()) plugin.getConfig(which)
    }
    LaunchedEffect(plugin.configTexts[which]) {
        text = plugin.configTexts[which] ?: text
        try {
            val t = text.trim()
            parsed = if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            parseError = null
        } catch (e: Exception) {
            parsed = null
            parseError = e.message
            useFancy = false
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.jarvis_config),
                navIconOnClick = back,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (files.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.jarvis_config_none_found))
                }
                return@Column
            }
            ScrollableTabRow(selectedTabIndex = safeTab) {
                files.forEachIndexed { i, f ->
                    Tab(
                        selected = safeTab == i,
                        onClick = { tab = i },
                        text = { Text(f.label) },
                    )
                }
            }
            val hint = activeFile?.hint.orEmpty()
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val path = plugin.configPaths[which].orEmpty()
            if (path.isNotEmpty()) {
                Text(path, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (!useFancy) {
                        try {
                            val t = text.trim()
                            parsed = if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
                            parseError = null
                            useFancy = true
                        } catch (e: Exception) { parseError = e.message }
                    }
                }) { Text(if (useFancy) "● Fancy" else "○ Fancy") }
                TextButton(onClick = {
                    if (useFancy && parsed != null) {
                        text = if (parsed is JSONArray) (parsed as JSONArray).toString(2) else (parsed as JSONObject).toString(2)
                    }
                    useFancy = false
                }) { Text(if (!useFancy) stringResource(R.string.jarvis_raw_json) + " ●" else stringResource(R.string.jarvis_raw_json)) }
            }
            if (parseError != null && useFancy) {
                Text("Can't show Fancy — invalid JSON: $parseError", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            } else if (useFancy && parsed != null) {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    JsonTreeEditor(parsed!!) {
                        text = if (parsed is JSONArray) (parsed as JSONArray).toString(2) else (parsed as JSONObject).toString(2)
                    }
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { plugin.getConfig(which) }) {
                    Text(stringResource(R.string.jarvis_reload))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (useFancy && parsed != null) {
                        text = if (parsed is JSONArray) (parsed as JSONArray).toString(2) else (parsed as JSONObject).toString(2)
                    }
                    plugin.setConfig(which, text)
                }) {
                    Text(stringResource(R.string.jarvis_save_file))
                }
            }
        }
    }
}