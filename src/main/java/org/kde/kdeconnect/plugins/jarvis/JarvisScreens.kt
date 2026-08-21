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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
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
                    if ((commandOutput && plugin.runBusy.value) || (!commandOutput && plugin.askBusy.value)) {
                        TextButton(onClick = {
                            plugin.cancel(if (commandOutput) "run" else "ask")
                        }) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AskScreen(plugin: JarvisPlugin, back: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var showConsole by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<JarvisChatMessage?>(null) }
    var menuFor by remember { mutableStateOf<JarvisChatMessage?>(null) }
    val messages = plugin.askMessages
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        JarvisSpeech.ensure(context)
    }
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
                    TextButton(onClick = { plugin.toggleMute() }) {
                        Text(
                            stringResource(
                                if (plugin.muted.value) R.string.jarvis_unmute else R.string.jarvis_mute,
                            ),
                        )
                    }
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
                        Box {
                            Card(
                                modifier = Modifier
                                    .widthIn(max = 320.dp)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuFor = msg
                                        },
                                    ),
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
                                    } else if (msg.fromUser) {
                                        Text(msg.text, color = textColor)
                                    } else {
                                        JarvisMarkdownText(msg.text, textColor)
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = menuFor === msg,
                                onDismissRequest = { menuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.jarvis_copy)) },
                                    onClick = {
                                        clipboard.setText(AnnotatedString(msg.text))
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.jarvis_copied),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        menuFor = null
                                    },
                                )
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
                    enabled = !plugin.askBusy.value,
                )
                if (plugin.askBusy.value) {
                    IconButton(onClick = { plugin.cancel("ask") }) {
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

@Composable
private fun ConfigScreen(plugin: JarvisPlugin, back: () -> Unit) {
    val tabs = listOf("commands", "ai", "playnite", "spotify", "memory")
    val labels = listOf(
        R.string.jarvis_config_commands,
        R.string.jarvis_config_ai,
        R.string.jarvis_config_playnite,
        R.string.jarvis_config_spotify,
        R.string.jarvis_config_memory,
    )
    var tab by remember { mutableIntStateOf(0) }
    val which = tabs[tab]
    var text by remember { mutableStateOf(plugin.configTexts[which] ?: "") }

    LaunchedEffect(which) {
        plugin.getConfig(which)
    }
    LaunchedEffect(plugin.configTexts[which]) {
        text = plugin.configTexts[which] ?: text
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
            ScrollableTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, _ ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(stringResource(labels[i])) },
                    )
                }
            }
            val path = plugin.configPaths[which].orEmpty()
            if (path.isNotEmpty()) {
                Text(path, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { plugin.getConfig(which) }) {
                    Text(stringResource(R.string.jarvis_reload))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { plugin.setConfig(which, text) }) {
                    Text(stringResource(R.string.jarvis_save_file))
                }
            }
        }
    }
}
