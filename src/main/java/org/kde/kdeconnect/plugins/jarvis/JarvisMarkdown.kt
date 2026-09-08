/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===========================================================================
// A small, dependency-free Markdown renderer for Jarvis's chat bubbles.
//
// The web app just hands the raw reply text to marked.js (see app.js's
// renderMarkdown / finalizeAskBubble: `bubble.innerHTML = renderMarkdown(raw)`).
// There's no Compose-friendly equivalent bundled with this project, and
// pulling in a third-party Markdown-for-Compose library isn't something we
// can verify will resolve cleanly against this project's Gradle/Compose BOM
// without an actual build, so this covers the common subset by hand:
// headings, bullet/numbered lists, fenced code blocks, blockquotes,
// horizontal rules, and inline **bold**/*italic*/`code`/[link](url) — enough
// to make typical Jarvis replies (which lean on lists and code fences) look
// right, without chasing full CommonMark parity.
//
// Only the assistant's own reply bubble is run through this — see
// JarvisScreens.kt's AskScreen, which still renders the user's own messages
// and the "thinking…" placeholder as plain Text, matching how the web app
// only calls renderMarkdown() on the Jarvis bubble.
// ===========================================================================

private val HEADING_LINE = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET_LINE = Regex("^\\s*[-*+]\\s+(.*)$")
private val NUMBERED_LINE = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
private val QUOTE_LINE = Regex("^\\s*>\\s?(.*)$")
private val RULE_LINE = Regex("^\\s*(---+|\\*\\*\\*+|___+)\\s*$")
private val FENCE_LINE = Regex("^\\s*```.*$")

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val number: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    object Rule : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            FENCE_LINE.matches(line) -> {
                flushParagraph()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !FENCE_LINE.matches(lines[i])) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(codeLines.joinToString("\n")))
                // Skip the closing fence (if present — an unterminated fence
                // at end-of-text just runs out of lines, which is fine).
            }
            RULE_LINE.matches(line) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }
            HEADING_LINE.matches(line) -> {
                flushParagraph()
                val m = HEADING_LINE.find(line)!!
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }
            BULLET_LINE.matches(line) -> {
                flushParagraph()
                val m = BULLET_LINE.find(line)!!
                blocks.add(MdBlock.Bullet(m.groupValues[1].trim()))
            }
            NUMBERED_LINE.matches(line) -> {
                flushParagraph()
                val m = NUMBERED_LINE.find(line)!!
                blocks.add(MdBlock.Numbered(m.groupValues[1], m.groupValues[2].trim()))
            }
            QUOTE_LINE.matches(line) -> {
                flushParagraph()
                val m = QUOTE_LINE.find(line)!!
                blocks.add(MdBlock.Quote(m.groupValues[1].trim()))
            }
            line.isBlank() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

// Inline spans: `code`, **bold**/__bold__, *italic*/_italic_, [text](url).
// Checked in that priority order per match position so code spans win over
// emphasis markers that might appear inside them.
private val INLINE_TOKEN = Regex(
    "`([^`]+)`" +
        "|\\*\\*([^*]+)\\*\\*" +
        "|__([^_]+)__" +
        "|\\*([^*]+)\\*" +
        "|_([^_]+)_" +
        "|\\[([^\\]]+)\\]\\(([^)]+)\\)",
)

private fun buildInlineAnnotatedString(line: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var idx = 0
    for (m in INLINE_TOKEN.findAll(line)) {
        if (m.range.first > idx) append(line.substring(idx, m.range.first))
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[1]!!.value) }
            g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]!!.value) }
            g[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]!!.value) }
            g[4] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]!!.value) }
            g[5] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]!!.value) }
            g[6] != null -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ) { append(g[6]!!.value) }
        }
        idx = m.range.last + 1
    }
    if (idx < line.length) append(line.substring(idx))
}

/**
 * Renders [text] as Markdown using the given base [color] for plain text
 * (headings/bold/links etc. adjust weight/color/decoration on top of it, so
 * this still reads correctly inside colored chat bubbles).
 */
@Composable
fun JarvisMarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val topPad = if (index == 0) 0.dp else 4.dp
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    }
                    Text(
                        buildInlineAnnotatedString(block.text, color),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = size,
                        modifier = Modifier.padding(top = topPad),
                    )
                }
                is MdBlock.Bullet -> {
                    Row(Modifier.padding(top = topPad)) {
                        Text("•  ", color = color)
                        Text(buildInlineAnnotatedString(block.text, color), color = color)
                    }
                }
                is MdBlock.Numbered -> {
                    Row(Modifier.padding(top = topPad)) {
                        Text("${block.number}.  ", color = color)
                        Text(buildInlineAnnotatedString(block.text, color), color = color)
                    }
                }
                is MdBlock.Quote -> {
                    Text(
                        buildInlineAnnotatedString(block.text, color),
                        color = color.copy(alpha = 0.8f),
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = topPad, start = 8.dp),
                    )
                }
                is MdBlock.Code -> {
                    Text(
                        block.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = topPad)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.12f))
                            .padding(8.dp),
                    )
                }
                MdBlock.Rule -> {
                    Row(
                        Modifier
                            .padding(top = topPad)
                            .background(color.copy(alpha = 0.3f)),
                    ) {
                        Text(" ", color = color)
                    }
                }
                is MdBlock.Paragraph -> {
                    Text(
                        buildInlineAnnotatedString(block.text, color),
                        color = color,
                        modifier = Modifier.padding(top = topPad),
                    )
                }
            }
        }
    }
}
