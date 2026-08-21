/**
 * SPDX-FileCopyrightText: 2026 Jarvis KDE Connect integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.jarvis

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** British male butler voice — closest stock TTS gets to Paul Bettany's Jarvis. */
object JarvisSpeech {
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (tts != null) {
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready.set(false)
                return@TextToSpeech
            }
            configure()
            ready.set(true)
        }
    }

    private fun configure() {
        val engine = tts ?: return
        val uk = engine.setLanguage(Locale.UK)
        if (uk == TextToSpeech.LANG_MISSING_DATA || uk == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(0.94f)
        engine.setPitch(0.72f)
        val voice = engine.voices
            ?.filter { isEnglish(it) }
            ?.maxByOrNull { score(it) }
        if (voice != null && score(voice) > 0) {
            engine.voice = voice
        }
    }

    private fun isEnglish(voice: Voice): Boolean {
        val lang = voice.locale?.language ?: return false
        if (!lang.equals("en", true)) {
            return false
        }
        val n = "${voice.name} ${voice.locale}".lowercase()
        if (n.contains("fr") || n.contains("french") || n.contains("francais") || n.contains("français")) {
            return false
        }
        return true
    }

    private fun score(voice: Voice): Int {
        val n = "${voice.name} ${voice.locale}".lowercase()
        var s = 0
        if (n.contains("female") || n.contains("woman") || n.contains("hazel") ||
            n.contains("zira") || n.contains("samantha") || n.contains("susan") ||
            n.contains("fable") || n.contains("aria") || n.contains("-gba-")
        ) {
            return -100
        }
        if (voice.locale.country.equals("GB", true) || n.contains("en-gb") || n.contains("en_gb")) s += 12
        if (n.contains("google uk english male") || n.contains("daniel") || n.contains("george") ||
            n.contains("-gbd-") || n.contains("-gbc-")
        ) {
            s += 16
        }
        if (n.contains("male") || n.contains("david") || n.contains("mark") || n.contains("brian") || n.contains("ryan")) {
            s += 8
        }
        if (n.contains("rishi") || n.contains("-rjs-") || n.contains("india") || n.contains("en-in")) s -= 12
        if (n.contains("irish") || n.contains("scottish") || n.contains("australian") || n.contains("en-au")) s -= 6
        if (voice.isNetworkConnectionRequired) s -= 1
        return s
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready.get()) {
            return
        }
        val spoken = speechText(text)
        if (spoken.isEmpty()) {
            return
        }
        val params = Bundle()
        engine.speak(spoken, TextToSpeech.QUEUE_ADD, params, "jarvis-${spoken.hashCode()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun speechText(raw: String): String {
        return raw
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), " ")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""[*_~]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
