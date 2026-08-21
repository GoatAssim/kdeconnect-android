/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

@Composable
fun JarvisMarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val parsed = remember(text, color) { parseJarvisMarkdown(text, color) }
    Text(parsed, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

fun parseJarvisMarkdown(source: String, color: Color): AnnotatedString {
    val cleaned = source
        .replace(Regex("^######?\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
    return buildAnnotatedString {
        append(cleaned)
        applyDelimited("***", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = color))
        applyDelimited("**", SpanStyle(fontWeight = FontWeight.Bold, color = color))
        applyDelimited("__", SpanStyle(fontWeight = FontWeight.Bold, color = color))
        applyDelimited("*", SpanStyle(fontStyle = FontStyle.Italic, color = color))
        applyDelimited("_", SpanStyle(fontStyle = FontStyle.Italic, color = color))
        applyDelimited("`", SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color))
        applyLinks(color)
    }
}

private fun AnnotatedString.Builder.applyDelimited(token: String, style: SpanStyle) {
    val text = toAnnotatedString().text
    var i = 0
    while (true) {
        val start = text.indexOf(token, i)
        if (start < 0) break
        val end = text.indexOf(token, start + token.length)
        if (end < 0) break
        addStyle(style, start, end + token.length)
        addStyle(SpanStyle(color = Color.Transparent, fontSize = 0.sp), start, start + token.length)
        addStyle(SpanStyle(color = Color.Transparent, fontSize = 0.sp), end, end + token.length)
        i = end + token.length
    }
}

private fun AnnotatedString.Builder.applyLinks(color: Color) {
    val text = toAnnotatedString().text
    val regex = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
    regex.findAll(text).forEach { match ->
        addStyle(
            SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium, color = color),
            match.range.first,
            match.range.last + 1,
        )
    }
}
